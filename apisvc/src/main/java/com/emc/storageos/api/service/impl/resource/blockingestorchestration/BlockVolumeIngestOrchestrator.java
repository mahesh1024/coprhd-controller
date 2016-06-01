/*
 * Copyright (c) 2008-2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.resource.blockingestorchestration;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.api.service.impl.resource.blockingestorchestration.IngestStrategyFactory.IngestStrategyEnum;
import com.emc.storageos.api.service.impl.resource.blockingestorchestration.IngestStrategyFactory.ReplicationStrategy;
import com.emc.storageos.api.service.impl.resource.blockingestorchestration.IngestStrategyFactory.VolumeType;
import com.emc.storageos.api.service.impl.resource.blockingestorchestration.context.IngestionRequestContext;
import com.emc.storageos.api.service.impl.resource.blockingestorchestration.context.impl.VplexVolumeIngestionContext;
import com.emc.storageos.api.service.impl.resource.utils.PropertySetterUtil;
import com.emc.storageos.api.service.impl.resource.utils.VolumeIngestionUtil;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.BlockSnapshot;
import com.emc.storageos.db.client.model.BlockSnapshotSession;
import com.emc.storageos.db.client.model.NamedURI;
import com.emc.storageos.db.client.model.OpStatusMap;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume.SupportedVolumeInformation;

/**
 * Responsible for ingesting block local volumes.
 */
public class BlockVolumeIngestOrchestrator extends BlockIngestOrchestrator {

    private static final Logger _logger = LoggerFactory.getLogger(BlockVolumeIngestOrchestrator.class);

    @Override
    protected <T extends BlockObject> T ingestBlockObjects(IngestionRequestContext requestContext, Class<T> clazz)
            throws IngestionException {

        UnManagedVolume unManagedVolume = requestContext.getCurrentUnmanagedVolume();
        boolean unManagedVolumeExported = requestContext.getVolumeContext().isVolumeExported();
        Volume volume = null;
        List<BlockSnapshotSession> snapSessions = new ArrayList<BlockSnapshotSession>();

        URI unManagedVolumeUri = unManagedVolume.getId();
        String volumeNativeGuid = unManagedVolume.getNativeGuid().replace(VolumeIngestionUtil.UNMANAGEDVOLUME,
                VolumeIngestionUtil.VOLUME);

        volume = VolumeIngestionUtil.checkIfVolumeExistsInDB(volumeNativeGuid, _dbClient);
        // Check if ingested volume has export masks pending for ingestion.
        if (isExportIngestionPending(volume, unManagedVolumeUri, unManagedVolumeExported)) {
            return clazz.cast(volume);
        }

        if (null == volume) {
            validateUnManagedVolume(unManagedVolume, requestContext.getVpool(unManagedVolume));
            // @TODO Need to revisit this. In 8.x Provider, ReplicationGroup is automatically created when a volume is associated to a
            // StorageGroup.
            // checkUnManagedVolumeAddedToCG(unManagedVolume, virtualArray, tenant, project, vPool);
            checkVolumeExportState(unManagedVolume, unManagedVolumeExported);
            checkVPoolValidForExportInitiatorProtocols(requestContext.getVpool(unManagedVolume), unManagedVolume);
            checkHostIOLimits(requestContext.getVpool(unManagedVolume), unManagedVolume, unManagedVolumeExported);

            StoragePool pool = validateAndReturnStoragePoolInVAarray(unManagedVolume, requestContext.getVarray(unManagedVolume));

            // validate quota is exceeded for storage systems and pools
            checkSystemResourceLimitsExceeded(requestContext.getStorageSystem(), unManagedVolume,
                    requestContext.getExhaustedStorageSystems());
            checkPoolResourceLimitsExceeded(requestContext.getStorageSystem(), pool, unManagedVolume, requestContext.getExhaustedPools());
            String autoTierPolicyId = getAutoTierPolicy(unManagedVolume, requestContext.getStorageSystem(),
                    requestContext.getVpool(unManagedVolume));
            validateAutoTierPolicy(autoTierPolicyId, unManagedVolume, requestContext.getVpool(unManagedVolume));

            volume = createVolume(requestContext, volumeNativeGuid, pool, unManagedVolume, autoTierPolicyId);
        }

        if (volume != null) {
            String syncActive = PropertySetterUtil.extractValueFromStringSet(
                    SupportedVolumeInformation.IS_SYNC_ACTIVE.toString(), unManagedVolume.getVolumeInformation());
            boolean isSyncActive = (null != syncActive) ? Boolean.parseBoolean(syncActive) : false;
            volume.setSyncActive(isSyncActive);

            if (VolumeIngestionUtil.isFullCopy(unManagedVolume)) {
                _logger.info("Setting clone related properties {}", unManagedVolume.getId());
                String replicaState = PropertySetterUtil.extractValueFromStringSet(
                        SupportedVolumeInformation.REPLICA_STATE.toString(), unManagedVolume.getVolumeInformation());
                volume.setReplicaState(replicaState);
                String replicationGroupName = PropertySetterUtil.extractValueFromStringSet(
                        SupportedVolumeInformation.FULL_COPY_CONSISTENCY_GROUP_NAME.toString(), unManagedVolume.getVolumeInformation());
                if (replicationGroupName != null && !replicationGroupName.isEmpty()) {
                    volume.setReplicationGroupInstance(replicationGroupName);
                }
            }

            // Create snapshot sessions for each synchronization aspect for the volume.
            StringSet syncAspectInfoForVolume = PropertySetterUtil.extractValuesFromStringSet(
                    SupportedVolumeInformation.SNAPSHOT_SESSIONS.toString(), unManagedVolume.getVolumeInformation());
            if ((syncAspectInfoForVolume != null) && (!syncAspectInfoForVolume.isEmpty())) {
                Project project = requestContext.getProject();
                // If this is a vplex backend volume, then the front end project should be set as snapshot session's project
                if (requestContext instanceof VplexVolumeIngestionContext && VolumeIngestionUtil.isVplexBackendVolume(unManagedVolume)) {
                    project = ((VplexVolumeIngestionContext) requestContext).getFrontendProject();
                }

                for (String syncAspectInfo : syncAspectInfoForVolume) {
                    String[] syncAspectInfoComponents = syncAspectInfo.split(":");
                    String syncAspectName = syncAspectInfoComponents[0];
                    String syncAspectObjPath = syncAspectInfoComponents[1];

                    // Make sure it is not already created.
                    URIQueryResultList queryResults = new URIQueryResultList();
                    _dbClient.queryByConstraint(AlternateIdConstraint.Factory.getBlockSnapshotSessionBySessionInstance(syncAspectObjPath),
                            queryResults);
                    Iterator<URI> queryResultsIter = queryResults.iterator();
                    if (!queryResultsIter.hasNext()) {
                        BlockSnapshotSession session = new BlockSnapshotSession();
                        session.setId(URIUtil.createId(BlockSnapshotSession.class));
                        session.setLabel(syncAspectName);
                        session.setSessionLabel(syncAspectName);
                        session.setParent(new NamedURI(volume.getId(), volume.getLabel()));
                        session.setProject(new NamedURI(project.getId(), project.getLabel()));
                        session.setStorageController(volume.getStorageController());
                        session.setSessionInstance(syncAspectObjPath);
                        StringSet linkedTargetURIs = new StringSet();
                        URIQueryResultList snapshotQueryResults = new URIQueryResultList();
                        _dbClient.queryByConstraint(AlternateIdConstraint.Factory.getBlockSnapshotBySettingsInstance(syncAspectObjPath),
                                snapshotQueryResults);
                        Iterator<URI> snapshotQueryResultsIter = snapshotQueryResults.iterator();
                        while (snapshotQueryResultsIter.hasNext()) {
                            linkedTargetURIs.add(snapshotQueryResultsIter.next().toString());
                        }
                        session.setLinkedTargets(linkedTargetURIs);
                        session.setOpStatus(new OpStatusMap());
                        snapSessions.add(session);
                    }
                }
                if (!snapSessions.isEmpty()) {
                    _dbClient.createObject(snapSessions);
                }
            }
        }

        // Note that a VPLEX backend volume can also be a snapshot target volume.
        // When the VPLEX ingest orchestrator is executed, it gets the ingestion
        // strategy for the backend volume and executes it. If the backend volume
        // is both a snapshot and a VPLEX backend volume, this local volume ingest
        // strategy is invoked and a Volume instance will result. That is fine because
        // we need to represent that VPLEX backend volume. However, we also need a
        // BlockSnapshot instance to represent the snapshot target volume. Therefore,
        // if the unmanaged volume is also a snapshot target volume, we get and
        // execute the local snapshot ingest strategy to create this BlockSnapshot
        // instance and we add it to the created object list. Note that since the
        // BlockSnapshot is added to the created objects list and the Volume and
        // BlockSnapshot instance will have the same native GUID, we must be careful
        // about adding the Volume to the created object list in the VPLEX ingestion
        // strategy.
        BlockObject snapshot = null;
        if (VolumeIngestionUtil.isSnapshot(unManagedVolume)) {
            String strategyKey = ReplicationStrategy.LOCAL.name() + "_" + VolumeType.SNAPSHOT.name();
            IngestStrategy ingestStrategy = ingestStrategyFactory.getIngestStrategy(IngestStrategyEnum.getIngestStrategy(strategyKey));
            snapshot = ingestStrategy.ingestBlockObjects(requestContext, BlockSnapshot.class);
            requestContext.getBlockObjectsToBeCreatedMap().put(snapshot.getNativeGuid(), snapshot);
        }

        // Run this always when volume NO_PUBLIC_ACCESS
        if (markUnManagedVolumeInactive(requestContext, volume)) {
            _logger.info("All the related replicas and parent has been ingested ",
                    unManagedVolume.getNativeGuid());
            // mark inactive if this is not to be exported. Else, mark as
            // inactive after successful export. Do not mark inactive for RP volumes because the RP masks should still be ingested
            // even though they are not exported to host/cluster. UnManaged RP volumes will be marked inactive after successful ingestion of
            // RP masks.
            if (!unManagedVolumeExported && !VolumeIngestionUtil.checkUnManagedResourceIsRecoverPointEnabled(unManagedVolume)) {
                unManagedVolume.setInactive(true);
                requestContext.getUnManagedVolumesToBeDeleted().add(unManagedVolume);
            }
        } else if (volume != null) {
            _logger.info(
                    "Not all the parent/replicas of unManagedVolume {} have been ingested , hence marking as internal",
                    unManagedVolume.getNativeGuid());
            volume.addInternalFlags(INTERNAL_VOLUME_FLAGS);
            for (BlockSnapshotSession snapSession : snapSessions) {
                snapSession.addInternalFlags(INTERNAL_VOLUME_FLAGS);
            }
            _dbClient.updateObject(snapSessions);
        }

        return clazz.cast(volume);
    }

    @Override
    protected void validateAutoTierPolicy(String autoTierPolicyId, UnManagedVolume unManagedVolume, VirtualPool vPool) {
        String associatedSourceVolume = PropertySetterUtil.extractValueFromStringSet(
                SupportedVolumeInformation.LOCAL_REPLICA_SOURCE_VOLUME.toString(),
                unManagedVolume.getVolumeInformation());
        // Skip autotierpolicy validation for clones as we use same orchestration for both volume & clone.
        if (null != associatedSourceVolume) {
            return;
        } else {
            super.validateAutoTierPolicy(autoTierPolicyId, unManagedVolume, vPool);
        }
    }

    /**
     * Following steps are performed as part of this method execution.
     *
     * @TODO refactor the code to modularize responsibilities.
     *
     *       1. Checks whether unManagedVolume is protected by RP or VPLEX, if yes we willn't create backend CG.
     *       2. For regular volumes in unManaged CG, we will create CG when ingesting last volume in unmanaged CG.
     *       3. When ingesting last regular volume in unmanaged CG, we will check whether CG already exists in DB for the same project &
     *       tenant.
     *       If yes, we will reuse it.
     *       Otherwise, we will create new BlockConsistencyGroup for the unmanaged consistencyGroup.
     *
     */
    @Override
    protected BlockConsistencyGroup getConsistencyGroup(UnManagedVolume unManagedVolume, BlockObject blockObj,
            IngestionRequestContext context, DbClient dbClient) {
        if (VolumeIngestionUtil.checkUnManagedResourceAddedToConsistencyGroup(unManagedVolume)) {
            return VolumeIngestionUtil.getBlockObjectConsistencyGroup(unManagedVolume, blockObj, context, dbClient);
        }
        return null;
    }
}
