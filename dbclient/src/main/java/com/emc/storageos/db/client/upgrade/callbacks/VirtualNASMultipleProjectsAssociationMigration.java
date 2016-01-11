/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.db.client.upgrade.callbacks;

import java.net.URI;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.VirtualNAS;
import com.emc.storageos.db.client.upgrade.BaseCustomMigrationCallback;
import com.emc.storageos.db.client.util.NullColumnValueGetter;

public class VirtualNASMultipleProjectsAssociationMigration extends
        BaseCustomMigrationCallback {

    private static final Logger logger = LoggerFactory
            .getLogger(VirtualNASMultipleProjectsAssociationMigration.class);

    @Override
    public void process() {

        logger.info("Migration started");

        DbClient dbClient = getDbClient();

        try {
            List<URI> virtualNASUris = dbClient.queryByType(VirtualNAS.class,
                    true);
            Iterator<VirtualNAS> virtualNASIterator = dbClient
                    .queryIterativeObjects(VirtualNAS.class, virtualNASUris,
                            true);

            logger.info("Processing virtual NASs to set the associated project into a set...");

            while (virtualNASIterator.hasNext()) {

                VirtualNAS virtualNAS = virtualNASIterator.next();

                URI projectURI = virtualNAS.getProject();
                if (!NullColumnValueGetter.isNullURI(projectURI)) {
                    virtualNAS.associateProject(projectURI.toString());
                }
            }
        } catch (Exception ex) {
            logger.error("Exception occured while associating project to virtual NAS");
            logger.error(ex.getMessage(), ex);
        }

        logger.info("Migration completed successfully");

    }

}
