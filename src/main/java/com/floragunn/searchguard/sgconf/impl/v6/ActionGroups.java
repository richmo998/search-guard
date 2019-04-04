package com.floragunn.searchguard.sgconf.impl.v6;

import java.util.Collections;
import java.util.List;

import com.floragunn.searchguard.sgconf.Hideable;

public class ActionGroups implements Hideable {

   
    private boolean readonly;
    private boolean hidden;
    private List<String> permissions = Collections.emptyList();

    public ActionGroups() {
        super();
    }
    public boolean isReadonly() {
        return readonly;
    }
    public void setReadonly(boolean readonly) {
        this.readonly = readonly;
    }
    public boolean isHidden() {
        return hidden;
    }
    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }
    public List<String> getPermissions() {
        return permissions;
    }
    public void setPermissions(List<String> permissions) {
        this.permissions = permissions;
    }
    @Override
    public String toString() {
        return "ActionGroups [readonly=" + readonly + ", hidden=" + hidden + ", permissions=" + permissions + "]";
    }
    
    
}