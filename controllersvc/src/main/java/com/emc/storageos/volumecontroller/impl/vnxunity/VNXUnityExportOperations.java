/*
 * Copyright (c) 2016 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.vnxunity;

import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.vnxe.VNXeApiClient;
import com.emc.storageos.volumecontroller.impl.vnxe.VNXeExportOperations;

public class VNXUnityExportOperations extends VNXeExportOperations {

    @Override
    protected VNXeApiClient getVnxeClient(StorageSystem storage) {
        VNXeApiClient client = _clientFactory.getUnityClient(storage.getIpAddress(),
                storage.getPortNumber(), storage.getUsername(),
                storage.getPassword());

        return client;

    }

}
