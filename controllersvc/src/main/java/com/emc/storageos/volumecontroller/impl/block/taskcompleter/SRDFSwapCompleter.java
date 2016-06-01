/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.block.taskcompleter;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.Operation.Status;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;

import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SRDFSwapCompleter extends SRDFTaskCompleter {

    private static final Logger _log = LoggerFactory.getLogger(SRDFSwapCompleter.class);
    private Volume.LinkStatus successLinkStatus;

    public SRDFSwapCompleter(List<URI> ids, String opId, Volume.LinkStatus successLinkStatus) {
        super(ids, opId);
        this.successLinkStatus = successLinkStatus;
    }

    @Override
    protected void complete(DbClient dbClient, Status status, ServiceCoded coded) throws DeviceControllerException {
        try {
            setDbClient(dbClient);
            recordSRDFOperation(dbClient, OperationTypeEnum.SWAP_SRDF_VOLUME, status, getTargetVolume().getId().toString(),
                    getSourceVolume().getId().toString());
        } catch (Exception e) {
            _log.error("Failed updating status. SRDF Volume Swap {}, for task " + getOpId(), getId(), e);
        } finally {
            super.complete(dbClient, status, coded);
        }
    }

    @Override
    protected Volume.LinkStatus getVolumeSRDFLinkStatusForSuccess() {
        // Link status returned depends on whether link was already swapped.
        // See SRDFDeviceController.
        return successLinkStatus;
    }
}
