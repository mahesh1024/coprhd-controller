/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.vnxe.requests;

import java.util.List;

import com.emc.storageos.vnxe.VNXeConstants;
import com.emc.storageos.vnxe.models.VNXeCifsServer;

public class CifsServerListRequest extends KHRequests<VNXeCifsServer> {
    private static final String URL = "/api/types/cifsServer/instances";
    private static final String FIELDS = "name,smbProtocolVersions,smbcaSupported,nasServer,netbiosName,workgroup,health,fileInterfaces,domain";

    public CifsServerListRequest(KHClient client) {
        super(client);
        _url = URL;
        _fields = FIELDS;
    }

    public List<VNXeCifsServer> get() {
        _queryParams = null;
        return getDataForObjects(VNXeCifsServer.class);

    }

    /**
     * get nasServer's cifs servers.
     * 
     * @param nasServerId nasServer internal id
     * @return list of cifsServer
     */
    public List<VNXeCifsServer> getCifsServersForNasServer(String nasServerId) {
        setFilter(VNXeConstants.NASSERVER_FILTER + "\"" + nasServerId + "\"");

        return getDataForObjects(VNXeCifsServer.class);
    }

}
