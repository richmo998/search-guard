/*
 * Copyright 2015-2017 floragunn GmbH
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.floragunn.searchguard.configuration;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.get.MultiGetItemResponse;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.get.MultiGetResponse;
import org.elasticsearch.action.get.MultiGetResponse.Failure;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.threadpool.ThreadPool;

import com.fasterxml.jackson.databind.JsonNode;
import com.floragunn.searchguard.DefaultObjectMapper;
import com.floragunn.searchguard.sgconf.impl.CType;
import com.floragunn.searchguard.sgconf.impl.SgDynamicConfiguration;
import com.floragunn.searchguard.support.ConfigConstants;
import com.floragunn.searchguard.support.SearchGuardDeprecationHandler;
import com.floragunn.searchguard.support.SgUtils;

public class ConfigurationLoaderSG7 {

    protected final Logger log = LogManager.getLogger(this.getClass());
    private final Client client;
    private final String searchguardIndex;
    private final ClusterService cs;
    private final Settings settings;
    
    ConfigurationLoaderSG7(final Client client, ThreadPool threadPool, final Settings settings, ClusterService cs) {
        super();
        this.client = client;
        this.settings = settings;
        this.searchguardIndex = settings.get(ConfigConstants.SEARCHGUARD_CONFIG_INDEX_NAME, ConfigConstants.SG_DEFAULT_CONFIG_INDEX);
        this.cs = cs;
        log.debug("Index is: {}", searchguardIndex);
    }
    
    Map<CType, SgDynamicConfiguration<?>> load(final CType[] events, long timeout, TimeUnit timeUnit) throws InterruptedException, TimeoutException {
        final CountDownLatch latch = new CountDownLatch(events.length);
        final Map<CType, SgDynamicConfiguration<?>> rs = new HashMap<>(events.length);
        
        loadAsync(events, new ConfigCallback() {
            
            @Override
            public void success(SgDynamicConfiguration<?> dConf) {
                if(latch.getCount() <= 0) {
                    log.error("Latch already counted down (for {} of {})  (index={})", dConf.getCType().toLCString(), Arrays.toString(events), searchguardIndex);
                }
                
                rs.put(dConf.getCType(), dConf);
                latch.countDown();
                if(log.isDebugEnabled()) {
                    log.debug("Received config for {} (of {}) with current latch value={}", dConf.getCType().toLCString(), Arrays.toString(events), latch.getCount());
                }
            }
            
            @Override
            public void singleFailure(Failure failure) {
                log.error("Failure {} retrieving configuration for {} (index={})", failure==null?null:failure.getMessage(), Arrays.toString(events), searchguardIndex);
            }
            
            @Override
            public void noData(String id, String type) {
                
                //when index was created with ES 6 there are no separate tenants. So we load just empty ones.
               //when index was created with ES 7 and type not "sg" (ES 6 type) there are no rolemappings anymore.
                if(cs.state().metaData().index(searchguardIndex).getCreationVersion().before(Version.V_7_0_0) || "sg".equals(type)) {
                    //created with SG 6
                    //skip tenants
                    
                    if(log.isDebugEnabled()) {
                        log.debug("Skip tenants because we not yet migrated to ES 7 (index was created with ES 6 and type is legacy [{}])", type);
                    }
                    
                    if(CType.fromString(id) == CType.TENANTS) {
                        rs.put(CType.fromString(id), SgDynamicConfiguration.empty());
                        latch.countDown();
                        return;
                    }
                }
                
                log.warn("No data for {} while retrieving configuration for {}  (index={} and type={})", id, Arrays.toString(events), searchguardIndex, type);
            }
            
            @Override
            public void failure(Throwable t) {
                log.error("Exception {} while retrieving configuration for {}  (index={})",t,t.toString(), Arrays.toString(events), searchguardIndex);
            }
        });
        
        if(!latch.await(timeout, timeUnit)) {
            //timeout
            throw new TimeoutException("Timeout after "+timeout+""+timeUnit+" while retrieving configuration for "+Arrays.toString(events)+ "(index="+searchguardIndex+")");
        }
        
        return rs;
    }
    
    void loadAsync(final CType[] events, final ConfigCallback callback) {
        if(events == null || events.length == 0) {
            log.warn("No config events requested to load");
            return;
        }
        
        final MultiGetRequest mget = new MultiGetRequest();

        for (int i = 0; i < events.length; i++) {
            final String event = events[i].toLCString();
            mget.add(searchguardIndex, event);
        }
        
        mget.refresh(true);
        mget.realtime(true);
        
        client.multiGet(mget, new ActionListener<MultiGetResponse>() {
            @Override
            public void onResponse(MultiGetResponse response) {
                MultiGetItemResponse[] responses = response.getResponses();
                for (int i = 0; i < responses.length; i++) {
                    MultiGetItemResponse singleResponse = responses[i];
                    if(singleResponse != null && !singleResponse.isFailed()) {
                        GetResponse singleGetResponse = singleResponse.getResponse();
                        if(singleGetResponse.isExists() && !singleGetResponse.isSourceEmpty()) {
                            //success
                            try {
                                final SgDynamicConfiguration<?> dConf = toConfig(singleGetResponse);
                                if(dConf != null) {
                                    callback.success(dConf.deepClone());
                                } else {
                                    callback.failure(new Exception("Cannot parse settings for "+singleGetResponse.getId()));
                                }
                            } catch (Exception e) {
                                log.error(e.toString(),e);
                                callback.failure(e);
                            }
                        } else {
                            //does not exist or empty source
                            callback.noData(singleGetResponse.getId(), singleGetResponse.getType());
                        }
                    } else {
                        //failure
                        callback.singleFailure(singleResponse==null?null:singleResponse.getFailure());
                    }
                }
            }
            
            @Override
            public void onFailure(Exception e) {
                callback.failure(e);
            }
        });
        
    }

    private SgDynamicConfiguration<?> toConfig(GetResponse singleGetResponse) throws Exception {
        final BytesReference ref = singleGetResponse.getSourceAsBytesRef();
        final String id = singleGetResponse.getId();
        final long seqNo = singleGetResponse.getSeqNo();
        final long primaryTerm = singleGetResponse.getPrimaryTerm();
        
        

        if (ref == null || ref.length() == 0) {
            log.error("Empty or null byte reference for {}", id);
            return null;
        }
        
        XContentParser parser = null;

        try {
            parser = XContentHelper.createParser(NamedXContentRegistry.EMPTY, SearchGuardDeprecationHandler.INSTANCE, ref, XContentType.JSON);
            parser.nextToken();
            parser.nextToken();
         
            if(!id.equals((parser.currentName()))) {
                log.error("Cannot parse config for type {} because {}!={}", id, id, parser.currentName());
                return null;
            }
            
            parser.nextToken();
            
            final String jsonAsString = SgUtils.replaceEnvVars(new String(parser.binaryValue()), settings);
            final JsonNode jsonNode = DefaultObjectMapper.readTree(jsonAsString);
            int configVersion = 1;
            
            if(jsonNode.get("_sg_meta") != null) {
                assert jsonNode.get("_sg_meta").get("type").asText().equals(id);
                configVersion = jsonNode.get("_sg_meta").get("config_version").asInt();
            }

            if(log.isDebugEnabled()) {
                log.debug("Load "+id+" with version "+configVersion);
            }
            
            if (CType.ACTIONGROUPS.toLCString().equals(id)) {
                try {
                    return SgDynamicConfiguration.fromJson(jsonAsString, CType.fromString(id), configVersion, seqNo, primaryTerm);
                } catch (Exception e) {
                    if(log.isDebugEnabled()) {
                        log.debug("Unable to load "+id+" with version "+configVersion+" - Try loading legacy format ...");
                    }
                    return SgDynamicConfiguration.fromJson(jsonAsString, CType.fromString(id), 0, seqNo, primaryTerm);
                }
            }

            return SgDynamicConfiguration.fromJson(jsonAsString, CType.fromString(id), configVersion, seqNo, primaryTerm);

        } finally {
            if(parser != null) {
                try {
                    parser.close();
                } catch (IOException e) {
                    //ignore
                }
            }
        }
    }
}