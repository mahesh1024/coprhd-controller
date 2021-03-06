/*
 * Copyright (c) 2016 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.volumecontroller.impl.externaldevice;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.emc.storageos.storagedriver.StorageDriver;
import com.emc.storageos.volumecontroller.impl.smis.ReplicationUtils;
import com.google.common.base.Joiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.BlockSnapshot;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.db.exceptions.DatabaseException;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.storagedriver.AbstractStorageDriver;
import com.emc.storageos.storagedriver.BlockStorageDriver;
import com.emc.storageos.storagedriver.DriverTask;
import com.emc.storageos.storagedriver.LockManager;
import com.emc.storageos.storagedriver.Registry;
import com.emc.storageos.storagedriver.impl.LockManagerImpl;
import com.emc.storageos.storagedriver.impl.RegistryImpl;
import com.emc.storageos.storagedriver.model.StorageObject;
import com.emc.storageos.storagedriver.model.StorageVolume;
import com.emc.storageos.storagedriver.model.VolumeClone;
import com.emc.storageos.storagedriver.model.VolumeConsistencyGroup;
import com.emc.storageos.storagedriver.model.VolumeSnapshot;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.volumecontroller.ControllerLockingService;
import com.emc.storageos.volumecontroller.DefaultBlockStorageDevice;
import com.emc.storageos.volumecontroller.TaskCompleter;
import com.emc.storageos.volumecontroller.impl.ControllerUtils;
import com.emc.storageos.volumecontroller.impl.NativeGUIDGenerator;
import com.emc.storageos.volumecontroller.impl.VolumeURIHLU;
import com.emc.storageos.volumecontroller.impl.smis.ExportMaskOperations;
import com.emc.storageos.volumecontroller.impl.utils.VirtualPoolCapabilityValuesWrapper;
import com.google.common.base.Strings;

/**
 * BlockStorageDevice implementation for device drivers.
 * Note: If references to driver model instances are used in internal hash maps, wrap  collections in unmodifiable view when calling
 * driver. For example: use Collections.unmodifiableList(modifiableList) for List collections.
 */
public class ExternalBlockStorageDevice extends DefaultBlockStorageDevice {

    private Logger _log = LoggerFactory.getLogger(ExternalBlockStorageDevice.class);
    // Storage drivers for block  devices
    private Map<String, AbstractStorageDriver> drivers;
    private DbClient dbClient;
    private ControllerLockingService locker;
    private ExportMaskOperations exportMaskOperationsHelper;

    // Initialized drivers map
    private Map<String, BlockStorageDriver> blockDrivers  = new HashMap<>();


    public void setDbClient(DbClient dbClient) {
        this.dbClient = dbClient;
    }

    public void setLocker(ControllerLockingService locker) {
        this.locker = locker;
    }

    public void setDrivers(Map<String, AbstractStorageDriver> drivers) {
        this.drivers = drivers;
    }

    public void setExportMaskOperationsHelper(ExportMaskOperations exportMaskOperationsHelper) {
        this.exportMaskOperationsHelper = exportMaskOperationsHelper;
    }

    public synchronized BlockStorageDriver getDriver(String driverType) {
        // look up driver
        BlockStorageDriver storageDriver = blockDrivers.get(driverType);
        if (storageDriver != null) {
            return storageDriver;
        } else {
            // init driver
            AbstractStorageDriver driver = drivers.get(driverType);
            if (driver == null) {
                _log.error("No driver entry defined for device type: {} . ", driverType);
                throw ExternalDeviceException.exceptions.noDriverDefinedForDevice(driverType);
            }
            init(driver);
            blockDrivers.put(driverType, (BlockStorageDriver)driver);
            return (BlockStorageDriver)driver;
        }
    }

    private void init(AbstractStorageDriver driver) {
        Registry driverRegistry = RegistryImpl.getInstance(dbClient);
        driver.setDriverRegistry(driverRegistry);
        LockManager lockManager = LockManagerImpl.getInstance(locker);
        driver.setLockManager(lockManager);
        driver.setSdkVersionNumber(StorageDriver.SDK_VERSION_NUMBER);
    }


    @Override
    public void doCreateVolumes(StorageSystem storageSystem, StoragePool storagePool,
                                String opId, List<Volume> volumes,
                                VirtualPoolCapabilityValuesWrapper capabilities,
                                TaskCompleter taskCompleter) throws DeviceControllerException {

        BlockStorageDriver driver = getDriver(storageSystem.getSystemType());

        List<StorageVolume> driverVolumes = new ArrayList<>();
        Map<StorageVolume, Volume> driverVolumeToVolumeMap = new HashMap<>();
        Set<URI> consistencyGroups = new HashSet<>();
        try {
            for (Volume volume : volumes) {
                StorageVolume driverVolume = new StorageVolume();
                driverVolume.setStorageSystemId(storageSystem.getNativeId());
                driverVolume.setStoragePoolId(storagePool.getNativeId());
                driverVolume.setRequestedCapacity(volume.getCapacity());
                driverVolume.setThinlyProvisioned(volume.getThinlyProvisioned());
                driverVolume.setDisplayName(volume.getLabel());
                if (!NullColumnValueGetter.isNullURI(volume.getConsistencyGroup())) {
                    BlockConsistencyGroup cg = dbClient.queryObject(BlockConsistencyGroup.class, volume.getConsistencyGroup());
                    driverVolume.setConsistencyGroup(cg.getNativeId());
                }

                driverVolumes.add(driverVolume);
                driverVolumeToVolumeMap.put(driverVolume, volume);
            }
            // Call driver
            DriverTask task = driver.createVolumes(Collections.unmodifiableList(driverVolumes), null);
            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY || task.getStatus() == DriverTask.TaskStatus.PARTIALLY_FAILED ) {

                updateVolumesWithDriverVolumeInfo(dbClient, driverVolumeToVolumeMap, consistencyGroups);
                dbClient.updateObject(driverVolumeToVolumeMap.values());
                updateConsistencyGroupsWithStorageSystem(consistencyGroups, storageSystem);
                String msg = String.format("doCreateVolumes -- Created volumes: %s .", task.getMessage());
                _log.info(msg);
                taskCompleter.ready(dbClient);
            } else {
                // Set volumes to inactive state
                for (Volume volume : volumes) {
                    volume.setInactive(true);
                }
                dbClient.updateObject(volumes);
                String errorMsg = String.format("doCreateVolumes -- Failed to create volumes: %s .", task.getMessage());
                _log.error(errorMsg);
                ServiceError serviceError = ExternalDeviceException.errors.createVolumesFailed("doCreateVolumes", errorMsg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (Exception e) {
            _log.error("doCreateVolumes -- Failed to create volumes. ", e);
            ServiceError serviceError = ExternalDeviceException.errors.createVolumesFailed("doCreateVolumes", e.getMessage());
            taskCompleter.error(dbClient, serviceError);
        }
    }

    @Override
    public void doExpandVolume(StorageSystem storageSystem, StoragePool storagePool,
                               Volume volume, Long size, TaskCompleter taskCompleter)
            throws DeviceControllerException {

        _log.info("Volume expand ..... Started");
        BlockStorageDriver driver = getDriver(storageSystem.getSystemType());
        DriverTask task = null;

        try {
            // Prepare driver volume
            StorageVolume driverVolume = new StorageVolume();
            driverVolume.setNativeId(volume.getNativeId());
            driverVolume.setStorageSystemId(storageSystem.getNativeId());
            driverVolume.setStoragePoolId(storagePool.getNativeId());
            driverVolume.setRequestedCapacity(volume.getCapacity());
            driverVolume.setThinlyProvisioned(volume.getThinlyProvisioned());
            driverVolume.setDisplayName(volume.getLabel());
            driverVolume.setAllocatedCapacity(volume.getAllocatedCapacity());
            driverVolume.setProvisionedCapacity(volume.getProvisionedCapacity());
            driverVolume.setWwn(volume.getWWN());

            // call driver
            task = driver.expandVolume(driverVolume, size);
            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                volume.setCapacity(driverVolume.getRequestedCapacity());
                volume.setProvisionedCapacity(driverVolume.getProvisionedCapacity());
                volume.setAllocatedCapacity(driverVolume.getAllocatedCapacity());
                dbClient.updateObject(volume);

                String msg = String.format("doExpandVolume -- Expanded volume: %s .", task.getMessage());
                _log.info(msg);
                taskCompleter.ready(dbClient);
            } else {
                // operation failed
                String errorMsg = String.format("doExpandVolume -- Failed to expand volume: %s .", task.getMessage());
                _log.error(errorMsg);
                ServiceError serviceError = ExternalDeviceException.errors.expandVolumeFailed("doExpandVolume", errorMsg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (Exception e) {
            _log.error("doCreateVolumes -- Failed to expand volume. ", e);
            ServiceError serviceError = ExternalDeviceException.errors.expandVolumeFailed("doExpandVolume", e.getMessage());
            taskCompleter.error(dbClient, serviceError);
        } finally {
            storagePool.removeReservedCapacityForVolumes(Collections.singletonList(volume.getId().toString()));
        }
    }

    /**
     * {@inheritDoc}
     *
     */
    @Override
    public void doDeleteVolumes(StorageSystem storageSystem, String opId,
                                List<Volume> volumes, TaskCompleter taskCompleter) throws DeviceControllerException {

        BlockStorageDriver driver = getDriver(storageSystem.getSystemType());

        List<Volume> deletedVolumes = new ArrayList<>();
        List<String> failedToDelete = new ArrayList<>();
        List<Volume> deletedClones = new ArrayList<>();
        List<String> failedToDeleteClones = new ArrayList<>();
        boolean exception = false;

        try {
            for (Volume volume : volumes) {
                DriverTask task = null;
                // Check if this is regular volume or this is volume clone
                if (!NullColumnValueGetter.isNullURI(volume.getAssociatedSourceVolume())) {
                    // this is clone
                    _log.info("Deleting volume clone on storage system {}, clone: {} .",
                            storageSystem.getNativeId(), volume.getNativeId());
                    BlockObject sourceVolume = BlockObject.fetch(dbClient, volume.getAssociatedSourceVolume());
                    VolumeClone driverClone = new VolumeClone();
                    driverClone.setStorageSystemId(storageSystem.getNativeId());
                    driverClone.setNativeId(volume.getNativeId());
                    driverClone.setParentId(sourceVolume.getNativeId());
                    driverClone.setConsistencyGroup(volume.getReplicationGroupInstance());
                    task = driver.deleteVolumeClone(Collections.unmodifiableList(Collections.singletonList(driverClone)));
                } else {
                    // this is regular volume
                    _log.info("Deleting volume on storage system {}, volume: {} .",
                            storageSystem.getNativeId(), volume.getNativeId());
                    StorageVolume driverVolume = new StorageVolume();
                    driverVolume.setStorageSystemId(storageSystem.getNativeId());
                    driverVolume.setNativeId(volume.getNativeId());
                    task = driver.deleteVolumes(Collections.unmodifiableList(Collections.singletonList(driverVolume)));
                }
                if (task.getStatus() == DriverTask.TaskStatus.READY) {
                    volume.setInactive(true);
                    if (volume.getAssociatedSourceVolume() != null) {
                        deletedClones.add(volume);
                    } else {
                        deletedVolumes.add(volume);
                    }
                } else {
                    if (volume.getAssociatedSourceVolume() != null) {
                        failedToDeleteClones.add(volume.getNativeId());
                    } else {
                        failedToDelete.add(volume.getNativeId());
                    }
                }
            }
        } catch (Exception e) {
            exception = true;
            _log.error("doDeleteVolumes -- Failed to delete volumes. ", e);
            ServiceError serviceError = ExternalDeviceException.errors.deleteVolumesFailed("doDeleteVolumes", e.getMessage());
            taskCompleter.error(dbClient, serviceError);
        } finally {
            if (!deletedVolumes.isEmpty()){
                _log.info("Deleted volumes on storage system {}, volumes: {} .",
                        storageSystem.getNativeId(), deletedVolumes.toString());
                dbClient.updateObject(deletedVolumes);
            }

            if (!deletedClones.isEmpty()){
                _log.info("Deleted volume clones on storage system {}, clones: {} .",
                        storageSystem.getNativeId(), deletedClones.toString());
                dbClient.updateObject(deletedClones);
            }

            if(!(failedToDelete.isEmpty() && failedToDeleteClones.isEmpty())) {
                String errorMsgVolumes = "";
                String errorMsgClones = "";
                if(!failedToDelete.isEmpty()) {
                    errorMsgVolumes = String.format("Failed to delete volumes on storage system %s, volumes: %s . ",
                            storageSystem.getNativeId(), failedToDelete.toString());
                    _log.error(errorMsgVolumes);
                } else {
                    errorMsgClones = String.format("Failed to delete volume clones on storage system %s, clones: %s .",
                            storageSystem.getNativeId(), failedToDeleteClones.toString());
                    _log.error(errorMsgClones);
                }

                ServiceError serviceError = ExternalDeviceException.errors.deleteVolumesFailed("doDeleteVolumes",
                        errorMsgVolumes + errorMsgClones);
                taskCompleter.error(dbClient, serviceError);
            } else if (!exception){
                taskCompleter.ready(dbClient);
            }
        }
    }

    @Override
    public void doCreateSingleSnapshot(StorageSystem storage, List<URI> snapshotList, Boolean createInactive, Boolean readOnly, TaskCompleter taskCompleter) throws DeviceControllerException {
        super.doCreateSingleSnapshot(storage, snapshotList, createInactive, readOnly, taskCompleter);
    }

    @Override
    public void doCreateSnapshot(StorageSystem storage, List<URI> snapshotList, Boolean createInactive, Boolean readOnly, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        try {
            Iterator<BlockSnapshot> snapshots = dbClient.queryIterativeObjects(BlockSnapshot.class, snapshotList);
            List<BlockSnapshot> blockSnapshots = new ArrayList<>();
            while (snapshots.hasNext()) {
               blockSnapshots.add(snapshots.next());
            }

            if (ControllerUtils.checkSnapshotsInConsistencyGroup(blockSnapshots, dbClient, taskCompleter)) {
                // all snapshots should be for the same CG (enforced by controller)
                createGroupSnapshots(storage, blockSnapshots, createInactive, readOnly, taskCompleter);
            } else {
                createVolumeSnapshots(storage, blockSnapshots, createInactive, readOnly, taskCompleter);
            }
        } catch (DatabaseException e) {
            String message = String.format("IO exception when trying to create snapshot(s) on array %s",
                    storage.getSerialNumber());
            _log.error(message, e);
            ServiceError error = ExternalDeviceException.errors.createSnapshotsFailed("doCreateSnapshot", e.getMessage());
            taskCompleter.error(dbClient, error);
        }
    }

    @Override
    public void doRestoreFromSnapshot(StorageSystem storageSystem, URI volume,
                                      URI snapshot, TaskCompleter taskCompleter)
            throws DeviceControllerException {

        String storageSystemNativeId = storageSystem.getNativeId();
        _log.info("Snapshot Restore..... Started");
        BlockConsistencyGroup parentVolumeConsistencyGroup = null;
        try {
            List<BlockSnapshot> snapshotsToRestore = new ArrayList<>();
            BlockSnapshot blockSnapshot = dbClient.queryObject(BlockSnapshot.class, snapshot);
            List<BlockSnapshot> groupSnapshots = ControllerUtils.getSnapshotsPartOfReplicationGroup(blockSnapshot, dbClient);
            if (groupSnapshots.size() > 1 &&
                    ControllerUtils.checkSnapshotsInConsistencyGroup(Arrays.asList(blockSnapshot), dbClient, taskCompleter)) {
                // make sure we restore only snapshots from the same consistency group
                for (BlockSnapshot snap : groupSnapshots) {
                    if (snap.getConsistencyGroup().equals(blockSnapshot.getConsistencyGroup())) {
                        snapshotsToRestore.add(snap);
                    }
                }
                URI cgUri = blockSnapshot.getConsistencyGroup();
                parentVolumeConsistencyGroup = dbClient.queryObject(BlockConsistencyGroup.class, cgUri);
                _log.info("Restore group snapshot: group {}, snapshot set: {}, snapshots to restore: "
                                + Joiner.on("\t").join(snapshotsToRestore), parentVolumeConsistencyGroup.getNativeId(),
                        blockSnapshot.getReplicationGroupInstance());
            } else {
                Volume sourceVolume = getSnapshotParentVolume(blockSnapshot);
                snapshotsToRestore.add(blockSnapshot);
                _log.info("Restore single volume snapshot: volume {}, snapshot: {}", sourceVolume.getNativeId(), blockSnapshot.getNativeId());
            }
            // Prepare driver snapshots
            List<VolumeSnapshot> driverSnapshots = new ArrayList<>();
            for (BlockSnapshot snap : snapshotsToRestore) {
                VolumeSnapshot driverSnapshot = new VolumeSnapshot();
                Volume sourceVolume = getSnapshotParentVolume(blockSnapshot);
                driverSnapshot.setParentId(sourceVolume.getNativeId());
                driverSnapshot.setNativeId(snap.getNativeId());
                driverSnapshot.setStorageSystemId(storageSystemNativeId);
                driverSnapshot.setDisplayName(snap.getLabel());
                if (parentVolumeConsistencyGroup != null) {
                    driverSnapshot.setConsistencyGroup(snap.getReplicationGroupInstance());
                }
                driverSnapshots.add(driverSnapshot);
            }

            // Call driver to execute this request
            BlockStorageDriver driver = getDriver(storageSystem.getSystemType());
            DriverTask task = driver.restoreSnapshot(driverSnapshots);
            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                String msg = String.format("doRestoreFromSnapshot -- Restored snapshots: %s .", task.getMessage());
                _log.info(msg);
                taskCompleter.ready(dbClient);
            } else {
                String errorMsg = String.format("doRestoreFromSnapshot -- Failed to restore from snapshots: %s .", task.getMessage());
                _log.error(errorMsg);
                ServiceError serviceError = ExternalDeviceException.errors.restoreFromSnapshotFailed("doRestoreFromSnapshot", errorMsg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (DatabaseException e) {
            String message = String.format("IO exception when trying to restore from snapshots on array %s",
                    storageSystem.getSerialNumber());
            _log.error(message, e);
            ServiceError error = ExternalDeviceException.errors.restoreFromSnapshotFailed("doRestoreFromSnapshot", e.getMessage());
            taskCompleter.error(dbClient, error);
        }
        _log.info("Snapshot Restore..... End");
    }


    @Override
    public void doDeleteSnapshot(StorageSystem storage, URI snapshot,
                                 TaskCompleter taskCompleter) throws DeviceControllerException {
        try {
            BlockSnapshot blockSnapshot = dbClient.queryObject(BlockSnapshot.class, snapshot);
            List<BlockSnapshot> groupSnapshots = ControllerUtils.getSnapshotsPartOfReplicationGroup(blockSnapshot, dbClient);

            if (groupSnapshots.size() > 1 &&
                    ControllerUtils.checkSnapshotsInConsistencyGroup(Arrays.asList(blockSnapshot), dbClient, taskCompleter)) {
                // make sure we delete only snapshots from the same consistency group
                List<BlockSnapshot> snapshotsToDelete = new ArrayList<>();
                for (BlockSnapshot snap : groupSnapshots ) {
                    if (snap.getConsistencyGroup().equals(blockSnapshot.getConsistencyGroup())) {
                        snapshotsToDelete.add(snap);
                    }
                }
                deleteGroupSnapshots(storage, snapshotsToDelete, taskCompleter);
            } else {
                deleteVolumeSnapshot(storage, snapshot, taskCompleter);
            }
        } catch (DatabaseException e) {
            String message = String.format(
                    "IO exception when trying to delete snapshot(s) on array %s", storage.getSerialNumber());
            _log.error(message, e);
            ServiceError error = ExternalDeviceException.errors.deleteSnapshotFailed("doDeleteSnapshot", e.getMessage());
            taskCompleter.error(dbClient, error);
        } catch (Exception e) {
            String message = String.format(
                    "Exception when trying to delete snapshot(s) on array %s", storage.getSerialNumber());
            _log.error(message, e);
            ServiceError error = ExternalDeviceException.errors.deleteSnapshotFailed("doDeleteSnapshot", e.getMessage());
            taskCompleter.error(dbClient, error);
        }
    }

    @Override
    public void doCreateClone(StorageSystem storageSystem, URI volume, URI clone, Boolean createInactive,
                              TaskCompleter taskCompleter) {
        Volume cloneObject = null;
        try {
            cloneObject = dbClient.queryObject(Volume.class, clone);
            Volume sourceVolume = dbClient.queryObject(Volume.class, volume);

            List<VolumeClone> driverClones = new ArrayList<>();
            // Prepare driver clone
            VolumeClone driverClone = new VolumeClone();
            driverClone.setParentId(sourceVolume.getNativeId());
            driverClone.setStorageSystemId(storageSystem.getNativeId());
            driverClone.setDisplayName(cloneObject.getLabel());
            driverClone.setRequestedCapacity(cloneObject.getCapacity());
            driverClone.setThinlyProvisioned(cloneObject.getThinlyProvisioned());
            driverClones.add(driverClone);

            // Call driver
            BlockStorageDriver driver = getDriver(storageSystem.getSystemType());
            DriverTask task = driver.createVolumeClone(Collections.unmodifiableList(driverClones), null);
            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                // Update clone
                VolumeClone driverCloneResult = driverClones.get(0);
                cloneObject.setNativeId(driverCloneResult.getNativeId());
                cloneObject.setWWN(driverCloneResult.getWwn());
                cloneObject.setDeviceLabel(driverCloneResult.getDeviceLabel());
                cloneObject.setNativeGuid(NativeGUIDGenerator.generateNativeGuid(dbClient, cloneObject));
                cloneObject.setReplicaState(driverCloneResult.getReplicationState().name());
                cloneObject.setProvisionedCapacity(driverCloneResult.getProvisionedCapacity());
                cloneObject.setAllocatedCapacity(driverCloneResult.getAllocatedCapacity());
                cloneObject.setInactive(false);
                dbClient.updateObject(cloneObject);

                String msg = String.format("doCreateClone -- Created volume clone: %s .", task.getMessage());
                _log.info(msg);
                taskCompleter.ready(dbClient);
            } else {
                cloneObject.setInactive(true);
                dbClient.updateObject(cloneObject);
                String errorMsg = String.format("doCreateClone -- Failed to create volume clone: %s .", task.getMessage());
                _log.error(errorMsg);
                ServiceError serviceError = ExternalDeviceException.errors.createVolumeCloneFailed("doCreateClone", errorMsg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (Exception e) {
            if (cloneObject != null) {
                cloneObject.setInactive(true);
                dbClient.updateObject(cloneObject);
            }
            _log.error("Failed to create volume clone. ", e);
            ServiceError serviceError = ExternalDeviceException.errors.createVolumeCloneFailed("doCreateClone", e.getMessage());
            taskCompleter.error(dbClient, serviceError);
        }
    }

    @Override
    public void doCreateGroupClone(StorageSystem storageSystem, List<URI> cloneURIs,
                                   Boolean createInactive, TaskCompleter taskCompleter) {
        BlockStorageDriver driver = getDriver(storageSystem.getSystemType());

        List<VolumeClone> driverClones = new ArrayList<>();
        Map<VolumeClone, Volume> driverCloneToCloneMap = new HashMap<>();
        Set<URI> consistencyGroups = new HashSet<>();

        List<Volume> clones = null;
        try {
            clones = dbClient.queryObject(Volume.class, cloneURIs);
            // We assume here that all volumes belong to the same consistency group
            URI parentUri = clones.get(0).getAssociatedSourceVolume();
            Volume parentVolume = dbClient.queryObject(Volume.class, parentUri);
            BlockConsistencyGroup cg = null;
            if (!NullColumnValueGetter.isNullURI(parentVolume.getConsistencyGroup())) {
                cg = dbClient.queryObject(BlockConsistencyGroup.class, parentVolume.getConsistencyGroup());
            } else {
                String errorMsg = String.format("doCreateGroupClone -- Failed to create group clone, parent volumes do not belong to consistency group." +
                        " Clones: %s .", cloneURIs);
                _log.error(errorMsg);
                ServiceError serviceError = ExternalDeviceException.errors.createGroupCloneFailed("doCreateGroupClone",errorMsg);
                taskCompleter.error(dbClient, serviceError);
                return;
            }
            // Prepare driver consistency group of parent volume
            VolumeConsistencyGroup driverCG = new VolumeConsistencyGroup();
            driverCG.setDisplayName(cg.getLabel());
            driverCG.setNativeId(cg.getNativeId());
            driverCG.setStorageSystemId(storageSystem.getNativeId());

            // Prepare driver clones
            for (Volume clone : clones) {
                URI sourceVolumeUri = clone.getAssociatedSourceVolume();
                Volume sourceVolume = dbClient.queryObject(Volume.class, sourceVolumeUri);
                VolumeClone driverClone = new VolumeClone();
                driverClone.setParentId(sourceVolume.getNativeId());
                driverClone.setStorageSystemId(storageSystem.getNativeId());
                driverClone.setDisplayName(clone.getLabel());
                driverClone.setRequestedCapacity(clone.getCapacity());
                driverClone.setThinlyProvisioned(clone.getThinlyProvisioned());
                driverClones.add(driverClone);
                driverCloneToCloneMap.put(driverClone, clone);
            }
            // Call driver to create group snapshot
            DriverTask task = driver.createConsistencyGroupClone(driverCG, Collections.unmodifiableList(driverClones), null);
            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                // Update clone object with driver data
                List<Volume> cloneObjects = new ArrayList<>();
                for (VolumeClone driverCloneResult : driverClones) {
                    Volume cloneObject = driverCloneToCloneMap.get(driverCloneResult);
                    cloneObject.setNativeId(driverCloneResult.getNativeId());
                    cloneObject.setWWN(driverCloneResult.getWwn());
                    cloneObject.setDeviceLabel(driverCloneResult.getDeviceLabel());
                    cloneObject.setNativeGuid(NativeGUIDGenerator.generateNativeGuid(dbClient, cloneObject));
                    cloneObject.setReplicaState(driverCloneResult.getReplicationState().name());
                    cloneObject.setReplicationGroupInstance(driverCloneResult.getConsistencyGroup());
                    cloneObject.setProvisionedCapacity(driverCloneResult.getProvisionedCapacity());
                    cloneObject.setAllocatedCapacity(driverCloneResult.getAllocatedCapacity());
                    cloneObject.setInactive(false);
                    cloneObject.setConsistencyGroup(parentVolume.getConsistencyGroup());
                    cloneObjects.add(cloneObject);
                }
                dbClient.updateObject(cloneObjects);
                String msg = String.format("doCreateGroupClone -- Created group clone: %s .", task.getMessage());
                _log.info(msg);
                taskCompleter.ready(dbClient);
            } else {
                // Process failure
                for (Volume cloneObject : clones) {
                    cloneObject.setInactive(true);
                }
                dbClient.updateObject(clones);
                String errorMsg = String.format("doCreateGroupClone -- Failed to create group clone: %s .", task.getMessage());
                _log.error(errorMsg);
                ServiceError serviceError = ExternalDeviceException.errors.createGroupCloneFailed("doCreateGroupClone", errorMsg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (Exception e) {
            if (clones != null) {
                // Process failure
                for (Volume cloneObject : clones) {
                    cloneObject.setInactive(true);
                }
                dbClient.updateObject(clones);
            }
            _log.error("Failed to create group clone. ", e);
            ServiceError serviceError = ExternalDeviceException.errors.createGroupCloneFailed("doCreateGroupClone", e.getMessage());
            taskCompleter.error(dbClient, serviceError);
        }
    }


    @Override
    public void doDetachClone(StorageSystem storageSystem, URI cloneVolume,
                              TaskCompleter taskCompleter) {
        BlockStorageDriver driver = getDriver(storageSystem.getSystemType());
        DriverTask task = null;
        Volume clone = dbClient.queryObject(Volume.class, cloneVolume);
        _log.info("Detaching volume clone on storage system {}, clone: {} .",
                storageSystem.getNativeId(), clone.toString());

        try {
            BlockObject sourceVolume = BlockObject.fetch(dbClient, clone.getAssociatedSourceVolume());
            VolumeClone driverClone = new VolumeClone();
            driverClone.setStorageSystemId(storageSystem.getNativeId());
            driverClone.setNativeId(clone.getNativeId());
            driverClone.setParentId(sourceVolume.getNativeId());
            driverClone.setConsistencyGroup(clone.getReplicationGroupInstance());

            // Call driver
            task = driver.detachVolumeClone(Collections.unmodifiableList(Collections.singletonList(driverClone)));
            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                ReplicationUtils.removeDetachedFullCopyFromSourceFullCopiesList(clone, dbClient);
                clone.setAssociatedSourceVolume(NullColumnValueGetter.getNullURI());
                clone.setReplicaState(Volume.ReplicationState.DETACHED.name());
                String msg = String.format("doDetachClone -- Detached volume clone: %s .", task.getMessage());
                _log.info(msg);
                dbClient.updateObject(clone);
                taskCompleter.ready(dbClient);
            } else {
                String msg = String.format("Failed to detach volume clone on storage system %s, clone: %s .",
                        storageSystem.getNativeId(), clone.toString());
                _log.error(msg);
                // todo: add error
                ServiceError serviceError = ExternalDeviceException.errors.detachVolumeCloneFailed("doDetachClone", msg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (Exception e) {
            String msg = String.format("Failed to detach volume clone on storage system %s, clone: %s .",
                    storageSystem.getNativeId(), clone.toString());
            _log.error(msg, e);
            ServiceError serviceError = ExternalDeviceException.errors.detachVolumeCloneFailed("doDetachClone", msg);
            taskCompleter.error(dbClient, serviceError);
        }
    }

    @Override
    public void doDetachGroupClone(StorageSystem storageSystem, List<URI> cloneVolumes,
                                   TaskCompleter taskCompleter) {

        BlockStorageDriver driver = getDriver(storageSystem.getSystemType());
        DriverTask task = null;
        List<Volume> clones = dbClient.queryObject(Volume.class, cloneVolumes);
        _log.info("Detaching group clones on storage system {}, clone: {} .",
                storageSystem.getNativeId(), clones.toString());

        try {
            Map<VolumeClone, Volume> driverCloneToCloneMap = new HashMap<>();
            List<VolumeClone> driverClones = new ArrayList<>();
            for (Volume clone : clones) {
                BlockObject sourceVolume = BlockObject.fetch(dbClient, clone.getAssociatedSourceVolume());
                VolumeClone driverClone = new VolumeClone();
                driverClone.setStorageSystemId(storageSystem.getNativeId());
                driverClone.setNativeId(clone.getNativeId());
                driverClone.setParentId(sourceVolume.getNativeId());
                driverClone.setConsistencyGroup(clone.getReplicationGroupInstance());
                driverClones.add(driverClone);
                driverCloneToCloneMap.put(driverClone, clone);
            }
            // Call driver
            task = driver.detachVolumeClone(Collections.unmodifiableList(driverClones));
            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                for (Map.Entry<VolumeClone, Volume> entry : driverCloneToCloneMap.entrySet() ) {
                    VolumeClone driverClone = entry.getKey();
                    Volume clone = entry.getValue();
                    ReplicationUtils.removeDetachedFullCopyFromSourceFullCopiesList(clone, dbClient);
                    clone.setAssociatedSourceVolume(NullColumnValueGetter.getNullURI());
                    clone.setReplicaState(Volume.ReplicationState.DETACHED.name());
                }

                String msg = String.format("doDetachGroupClone -- Detached group clone: %s .", task.getMessage());
                _log.info(msg);
                dbClient.updateObject(clones);
                taskCompleter.ready(dbClient);
            } else {
                String msg = String.format("Failed to detach group clone on storage system %s, clones: %s .",
                        storageSystem.getNativeId(), clones.toString());
                _log.error(msg);
                // todo: add error
                ServiceError serviceError = ExternalDeviceException.errors.detachVolumeCloneFailed("doDetachGroupClone", msg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (Exception e) {
            String msg = String.format("Failed to detach group clone on storage system %s, clones: %s .",
                    storageSystem.getNativeId(), clones.toString());
            _log.error(msg, e);
            // todo: add error
            ServiceError serviceError = ExternalDeviceException.errors.detachVolumeCloneFailed("doDetachGroupClone", msg);
            taskCompleter.error(dbClient, serviceError);
        }
    }

    @Override
    public void doRestoreFromClone(StorageSystem storageSystem, URI cloneVolume,
                                   TaskCompleter taskCompleter) {
        BlockStorageDriver driver = getDriver(storageSystem.getSystemType());
        DriverTask task = null;
        Volume clone = dbClient.queryObject(Volume.class, cloneVolume);
        _log.info("Restore from volume clone on storage system {}, clone: {} .",
                storageSystem.getNativeId(), clone.toString());

        try {
            BlockObject sourceVolume = BlockObject.fetch(dbClient, clone.getAssociatedSourceVolume());
            VolumeClone driverClone = new VolumeClone();

            driverClone.setStorageSystemId(storageSystem.getNativeId());
            driverClone.setNativeId(clone.getNativeId());
            driverClone.setParentId(sourceVolume.getNativeId());
            driverClone.setConsistencyGroup(clone.getReplicationGroupInstance());

            // Call driver
            task = driver.restoreFromClone(Collections.unmodifiableList(Collections.singletonList(driverClone)));
            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                clone.setReplicaState(driverClone.getReplicationState().name());
                String msg = String.format("doRestoreFromClone -- Restored volume from clone: %s .", task.getMessage());
                _log.info(msg);
                dbClient.updateObject(clone);
                taskCompleter.ready(dbClient);
            } else {
                String msg = String.format("Failed to restore volume from clone on storage system %s, clone: %s .",
                        storageSystem.getNativeId(), clone.toString());
                _log.error(msg);
                // todo: add error
                ServiceError serviceError = ExternalDeviceException.errors.restoreVolumesFromClonesFailed("doRestoreFromClone", msg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (Exception e) {
            String msg = String.format("Failed to restore volume from clone on storage system %s, clone: %s .",
                    storageSystem.getNativeId(), clone.toString());
            _log.error(msg, e);
            ServiceError serviceError = ExternalDeviceException.errors.restoreVolumesFromClonesFailed("doRestoreFromClone", msg);
            taskCompleter.error(dbClient, serviceError);
        }
    }

    @Override
    public void doRestoreFromGroupClone(StorageSystem storageSystem,
                                        List<URI> cloneVolumes, TaskCompleter taskCompleter) {
        BlockStorageDriver driver = getDriver(storageSystem.getSystemType());
        DriverTask task = null;
        List<Volume> clones = dbClient.queryObject(Volume.class, cloneVolumes);
        _log.info("Restore from group clone on storage system {}, clones: {} .",
                storageSystem.getNativeId(), clones.toString());

        try {
            Map<VolumeClone, Volume> driverCloneToCloneMap = new HashMap<>();
            List<VolumeClone> driverClones = new ArrayList<>();
            for (Volume clone : clones) {
                BlockObject sourceVolume = BlockObject.fetch(dbClient, clone.getAssociatedSourceVolume());
                VolumeClone driverClone = new VolumeClone();
                driverClone.setStorageSystemId(storageSystem.getNativeId());
                driverClone.setNativeId(clone.getNativeId());
                driverClone.setParentId(sourceVolume.getNativeId());
                driverClone.setConsistencyGroup(clone.getReplicationGroupInstance());
                driverClones.add(driverClone);
                driverCloneToCloneMap.put(driverClone, clone);
            }
            // Call driver
            task = driver.restoreFromClone(Collections.unmodifiableList(driverClones));
            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                for (Map.Entry<VolumeClone, Volume> entry : driverCloneToCloneMap.entrySet() ) {
                    VolumeClone driverClone = entry.getKey();
                    Volume clone = entry.getValue();
                    clone.setReplicaState(driverClone.getReplicationState().name());
                }

                String msg = String.format("doRestoreFromGroupClone -- Restore from group clone: %s .", task.getMessage());
                _log.info(msg);
                dbClient.updateObject(clones);
                taskCompleter.ready(dbClient);
            } else {
                String msg = String.format("Failed to restore from group clone on storage system %s, clones: %s .",
                        storageSystem.getNativeId(), clones.toString());
                _log.error(msg);
                ServiceError serviceError = ExternalDeviceException.errors.restoreVolumesFromClonesFailed("doRestoreFromGroupClone", msg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (Exception e) {
            String msg = String.format("Failed to restore from group clone on storage system %s, clones: %s .",
                    storageSystem.getNativeId(), clones.toString());
            _log.error(msg, e);
            ServiceError serviceError = ExternalDeviceException.errors.restoreVolumesFromClonesFailed("doRestoreFromGroupClone", msg);
            taskCompleter.error(dbClient, serviceError);
        }
    }


    @Override
    public void doCreateConsistencyGroup(StorageSystem storageSystem, URI consistencyGroup, String replicationGroupName, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _log.info("Creating consistency group for volumes START.....");
        BlockConsistencyGroup cg = null;
        try {
            VolumeConsistencyGroup driverCG = new VolumeConsistencyGroup();
            cg = dbClient.queryObject(BlockConsistencyGroup.class, consistencyGroup);
            driverCG.setDisplayName(cg.getLabel());
            driverCG.setStorageSystemId(storageSystem.getNativeId());
            // call driver
            BlockStorageDriver driver = getDriver(storageSystem.getSystemType());
            DriverTask task = driver.createConsistencyGroup(driverCG);
            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                cg.setNativeId(driverCG.getNativeId());
                dbClient.updateObject(cg);
                String msg = String.format("doCreateConsistencyGroup -- Created consistency group: %s .", task.getMessage());
                _log.info(msg);
                taskCompleter.ready(dbClient);
            } else {
                cg.setInactive(true);
                dbClient.updateObject(cg);
                String errorMsg = String.format("doCreateConsistencyGroup -- Failed to create Consistency Group: %s .", task.getMessage());
                _log.error(errorMsg);
                ServiceError serviceError = ExternalDeviceException.errors.createConsistencyGroupFailed("doCreateConsistencyGroup", errorMsg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (Exception e) {
            if (cg != null) {
                cg.setInactive(true);
                dbClient.updateObject(cg);
            }
            String errorMsg = String.format("doCreateConsistencyGroup -- Failed to create Consistency Group: %s .", e.getMessage());
            _log.error(errorMsg, e);
            ServiceError serviceError = ExternalDeviceException.errors.createConsistencyGroupFailed("doCreateConsistencyGroup", errorMsg);
            taskCompleter.error(dbClient, serviceError);
        } finally {
            _log.info("Creating consistency group for volumes END.....");
        }
    }

    @Override
    public void doDeleteConsistencyGroup(StorageSystem storageSystem,
                                         URI consistencyGroupId, String replicationGroupName,
                                         Boolean keepRGName,  Boolean markInactive, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _log.info("Delete consistency group: STARTED...");

        BlockConsistencyGroup consistencyGroup = null;
        String groupNativeId = null;
        String groupDisplayName = null;
        boolean isDeleteForBlockCG = true;

        try {
            if (!NullColumnValueGetter.isNullURI(consistencyGroupId)) {
                consistencyGroup = dbClient.queryObject(BlockConsistencyGroup.class, consistencyGroupId);
                groupDisplayName = consistencyGroup != null ? consistencyGroup.getLabel() : replicationGroupName;
                groupNativeId = consistencyGroup != null ? consistencyGroup.getNativeId() : replicationGroupName;
                if (consistencyGroup == null) {
                    isDeleteForBlockCG = false;
                }
            } else {
                groupDisplayName = replicationGroupName;
                groupNativeId = replicationGroupName;
                isDeleteForBlockCG = false;
            }

            if (groupNativeId == null || groupNativeId.isEmpty()) {
                String msg = String.format("doDeleteConsistencyGroup -- There is no consistency group or replication group to delete.");
                _log.info(msg);
                taskCompleter.ready(dbClient);
                return;
            }

            if (isDeleteForBlockCG) {
                _log.info("Deleting consistency group: storage system {}, group {}", storageSystem.getNativeId(), groupDisplayName );
            } else {
                _log.info("Deleting system replication group: storage system {}, group {}", storageSystem.getNativeId(), groupDisplayName );
                _log.info("Replication groups are not supported for external devices. Do not call driver." );
                taskCompleter.ready(dbClient);
                return;
            }

            // prepare driver consistency group
            VolumeConsistencyGroup driverCG = new VolumeConsistencyGroup();
            driverCG.setDisplayName(groupDisplayName);
            driverCG.setNativeId(groupNativeId);
            driverCG.setStorageSystemId(storageSystem.getNativeId());

            // call driver
            BlockStorageDriver driver = getDriver(storageSystem.getSystemType());

            DriverTask task = driver.deleteConsistencyGroup(driverCG);
            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                if (consistencyGroup != null) {
                    consistencyGroup.removeSystemConsistencyGroup(URIUtil.asString(storageSystem.getId()), groupDisplayName);
                    if (markInactive) {
                        consistencyGroup.setInactive(true);
                    }
                    dbClient.updateObject(consistencyGroup);
                }
                String msg = String.format("doDeleteConsistencyGroup -- Delete consistency group: %s .", task.getMessage());
                _log.info(msg);
                taskCompleter.ready(dbClient);
            } else {
                String errorMsg = String.format("doDeleteConsistencyGroup -- Failed to delete Consistency Group: %s .", task.getMessage());
                _log.error(errorMsg);
                ServiceError serviceError = ExternalDeviceException.errors.deleteConsistencyGroupFailed("doDeleteConsistencyGroup", errorMsg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (Exception e) {
            String errorMsg = String.format("doDeleteConsistencyGroup -- Failed to delete Consistency Group: %s .", e.getMessage());
            _log.error(errorMsg, e);
            ServiceError serviceError = ExternalDeviceException.errors.deleteConsistencyGroupFailed("doDeleteConsistencyGroup", errorMsg);
            taskCompleter.error(dbClient, serviceError);
        } finally {
            _log.info("Delete consistency group: END...");
        }
    }

    @Override
    public void doExportGroupCreate(StorageSystem storage,
                                    ExportMask exportMask, Map<URI, Integer> volumeMap,
                                    List<Initiator> initiators, List<URI> targets,
                                    TaskCompleter taskCompleter) throws DeviceControllerException {
        _log.info("{} doExportGroupCreate START ...", storage.getSerialNumber());
        VolumeURIHLU[] volumeLunArray = ControllerUtils.getVolumeURIHLUArray(storage.getSystemType(), volumeMap, dbClient);
        exportMaskOperationsHelper.createExportMask(storage, exportMask.getId(), volumeLunArray, targets, initiators, taskCompleter);
        _log.info("{} doExportGroupCreate END ...", storage.getSerialNumber());
    }

    @Override
    public void doExportAddVolume(StorageSystem storage, ExportMask exportMask,
                                  URI volume, Integer lun, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _log.info("{} doExportAddVolume START ...", storage.getSerialNumber());
        Map<URI, Integer> map = new HashMap<URI, Integer>();
        map.put(volume, lun);
        VolumeURIHLU[] volumeLunArray = ControllerUtils.getVolumeURIHLUArray(storage.getSystemType(), map, dbClient);
        exportMaskOperationsHelper.addVolume(storage, exportMask.getId(), volumeLunArray, taskCompleter);
        _log.info("{} doExportAddVolume END ...", storage.getSerialNumber());
    }


    @Override
    public void doExportAddVolumes(StorageSystem storage,
                                   ExportMask exportMask, Map<URI, Integer> volumes,
                                   TaskCompleter taskCompleter) throws DeviceControllerException {
        _log.info("{} doExportAddVolume START ...", storage.getSerialNumber());
        VolumeURIHLU[] volumeLunArray = ControllerUtils.getVolumeURIHLUArray(storage.getSystemType(), volumes, dbClient);
        exportMaskOperationsHelper.addVolume(storage, exportMask.getId(),
                volumeLunArray, taskCompleter);
        _log.info("{} doExportAddVolume END ...", storage.getSerialNumber());
    }

    @Override
    public void doExportRemoveVolume(StorageSystem storage,
                                     ExportMask exportMask, URI volume, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _log.info("{} doExportRemoveVolume START ...", storage.getSerialNumber());
        exportMaskOperationsHelper.removeVolume(storage, exportMask.getId(), Arrays.asList(volume), taskCompleter);
        _log.info("{} doExportRemoveVolume END ...", storage.getSerialNumber());
    }

    @Override
    public void doExportRemoveVolumes(StorageSystem storage,
                                      ExportMask exportMask, List<URI> volumes,
                                      TaskCompleter taskCompleter) throws DeviceControllerException {
        _log.info("{} doExportRemoveVolumes START ...", storage.getSerialNumber());
        exportMaskOperationsHelper.removeVolume(storage, exportMask.getId(), volumes,
                taskCompleter);
        _log.info("{} doExportRemoveVolumes END ...", storage.getSerialNumber());
    }

    @Override
    public void doExportAddInitiator(StorageSystem storage,
                                     ExportMask exportMask, Initiator initiator, List<URI> targets,
                                     TaskCompleter taskCompleter) throws DeviceControllerException {
        _log.info("{} doExportAddInitiator START ...", storage.getSerialNumber());
        exportMaskOperationsHelper.addInitiator(storage, exportMask.getId(), Arrays.asList(initiator), targets, taskCompleter);
        _log.info("{} doExportAddInitiator END ...", storage.getSerialNumber());
    }

    @Override
    public void doExportAddInitiators(StorageSystem storage,
                                      ExportMask exportMask, List<Initiator> initiators,
                                      List<URI> targets, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _log.info("{} doExportAddInitiators START ...", storage.getSerialNumber());
        exportMaskOperationsHelper.addInitiator(storage, exportMask.getId(), initiators, targets, taskCompleter);
        _log.info("{} doExportAddInitiators END ...", storage.getSerialNumber());
    }

    @Override
    public void doExportRemoveInitiator(StorageSystem storage,
                                        ExportMask exportMask, Initiator initiator, List<URI> targets,
                                        TaskCompleter taskCompleter) throws DeviceControllerException {
        _log.info("{} doExportRemoveInitiator START ...", storage.getSerialNumber());
        exportMaskOperationsHelper.removeInitiator(storage, exportMask.getId(), Arrays.asList(initiator), targets, taskCompleter);
        _log.info("{} doExportRemoveInitiator END ...", storage.getSerialNumber());
    }

    @Override
    public void doExportRemoveInitiators(StorageSystem storage,
                                         ExportMask exportMask, List<Initiator> initiators,
                                         List<URI> targets, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _log.info("{} doExportRemoveInitiators START ...", storage.getSerialNumber());
        exportMaskOperationsHelper.removeInitiator(storage, exportMask.getId(), initiators, targets, taskCompleter);
        _log.info("{} doExportRemoveInitiators END ...", storage.getSerialNumber());
    }

    @Override
    public void doExportGroupDelete(StorageSystem storage,
                                    ExportMask exportMask, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _log.info("{} doExportGroupDelete START ...", storage.getSerialNumber());
        exportMaskOperationsHelper.deleteExportMask(storage, exportMask.getId(), new ArrayList<URI>(),
                new ArrayList<URI>(), new ArrayList<Initiator>(), taskCompleter);
        _log.info("{} doExportGroupDelete END ...", storage.getSerialNumber());
    }

    @Override
    public ExportMask refreshExportMask(StorageSystem storage, ExportMask mask) {
        return exportMaskOperationsHelper.refreshExportMask(storage, mask);
    }

    @Override
    public void doConnect(StorageSystem storageSystem) {
        BlockStorageDriver driver = getDriver(storageSystem.getSystemType());
        if (driver == null) {
            throw DeviceControllerException.exceptions.connectStorageFailedNoDevice(
                    storageSystem.getSystemType());
        }
        _log.info("doConnect to external device {} - start", storageSystem.getId());
        _log.info("doConnect to external device {} - end", storageSystem.getId());
    }


    private void updateVolumesWithDriverVolumeInfo(DbClient dbClient, Map<StorageVolume, Volume> driverVolumesMap, Set<URI> consistencyGroups)
                  throws IOException {
        for (Map.Entry driverVolumeToVolume : driverVolumesMap.entrySet()) {
            StorageVolume driverVolume = (StorageVolume)driverVolumeToVolume.getKey();
            Volume volume = (Volume)driverVolumeToVolume.getValue();
            if (driverVolume.getNativeId() != null && driverVolume.getNativeId().length() > 0) {
                volume.setNativeId(driverVolume.getNativeId());
                volume.setDeviceLabel(driverVolume.getDeviceLabel());
                volume.setNativeGuid(NativeGUIDGenerator.generateNativeGuid(dbClient, volume));

                if (driverVolume.getWwn() == null) {
                    volume.setWWN(String.format("%s%s", driverVolume.getStorageSystemId(), driverVolume.getNativeId()));
                } else {
                    volume.setWWN(driverVolume.getWwn());
                }
                volume.setProvisionedCapacity(driverVolume.getProvisionedCapacity());
                volume.setAllocatedCapacity(driverVolume.getAllocatedCapacity());
                if (!NullColumnValueGetter.isNullURI(volume.getConsistencyGroup())) {
                    consistencyGroups.add(volume.getConsistencyGroup());
                }
            } else {
                volume.setInactive(true);
            }
        }
    }

    private void createVolumeSnapshots(StorageSystem storageSystem, List<BlockSnapshot> snapshots, Boolean createInactive, Boolean readOnly,
                                       TaskCompleter taskCompleter) {
        _log.info("Creating snapshots for volumes.....");
        List<VolumeSnapshot> driverSnapshots = new ArrayList<>();
        Map<VolumeSnapshot, BlockSnapshot> driverSnapshotToSnapshotMap = new HashMap<>();
        // Prepare driver snapshots
        String storageSystemNativeId = storageSystem.getNativeId();
        for (BlockSnapshot snapshot : snapshots) {
            Volume parent = dbClient.queryObject(Volume.class, snapshot.getParent().getURI());
            VolumeSnapshot driverSnapshot = new VolumeSnapshot();
            driverSnapshot.setParentId(parent.getNativeId());
            driverSnapshot.setStorageSystemId(storageSystemNativeId);
            driverSnapshot.setDisplayName(snapshot.getLabel());
            if (readOnly) {
               driverSnapshot.setAccessStatus(StorageObject.AccessStatus.READ_ONLY);
            } else {
                driverSnapshot.setAccessStatus(StorageObject.AccessStatus.READ_WRITE);
            }
            driverSnapshotToSnapshotMap.put(driverSnapshot, snapshot);
            driverSnapshots.add(driverSnapshot);
        }
        // call driver
        BlockStorageDriver driver = getDriver(storageSystem.getSystemType());
        DriverTask task = driver.createVolumeSnapshot(Collections.unmodifiableList(driverSnapshots), null);
        // todo: need to implement support for async case.
        if (task.getStatus() == DriverTask.TaskStatus.READY) {
            // update snapshots
            for (VolumeSnapshot driverSnapshot : driverSnapshotToSnapshotMap.keySet()) {
                BlockSnapshot snapshot = driverSnapshotToSnapshotMap.get(driverSnapshot);
                snapshot.setNativeId(driverSnapshot.getNativeId());
                snapshot.setDeviceLabel(driverSnapshot.getDeviceLabel());
                snapshot.setIsSyncActive(true);
                snapshot.setReplicationGroupInstance(driverSnapshot.getConsistencyGroup());
                if (driverSnapshot.getProvisionedCapacity() > 0) {
                    snapshot.setProvisionedCapacity(driverSnapshot.getProvisionedCapacity());
                }
                if (driverSnapshot.getAllocatedCapacity() > 0) {
                    snapshot.setAllocatedCapacity(driverSnapshot.getAllocatedCapacity());
                }
            }
            dbClient.updateObject(driverSnapshotToSnapshotMap.values());
            String msg = String.format("createVolumeSnapshots -- Created snapshots: %s .", task.getMessage());
            _log.info(msg);
            taskCompleter.ready(dbClient);
        } else {
            for (BlockSnapshot snapshot : snapshots) {
                snapshot.setInactive(true);
            }
            dbClient.updateObject(snapshots);
            String errorMsg = String.format("doCreateSnapshot -- Failed to create snapshots: %s .", task.getMessage());
            _log.error(errorMsg);
            ServiceError serviceError = ExternalDeviceException.errors.createSnapshotsFailed("doCreateSnapshot", errorMsg);
            taskCompleter.error(dbClient, serviceError);
        }
    }

    private void createGroupSnapshots(StorageSystem storageSystem, List<BlockSnapshot> snapshots, Boolean createInactive, Boolean readOnly,
                                       TaskCompleter taskCompleter) {
        _log.info("Creating snapshot of consistency group .....");
        List<VolumeSnapshot> driverSnapshots = new ArrayList<>();
        Map<VolumeSnapshot, BlockSnapshot> driverSnapshotToSnapshotMap = new HashMap<>();
        URI cgUri = snapshots.get(0).getConsistencyGroup();
        BlockConsistencyGroup consistencyGroup = dbClient.queryObject(BlockConsistencyGroup.class, cgUri);
        // Prepare driver snapshots
        String storageSystemNativeId = storageSystem.getNativeId();
        for (BlockSnapshot snapshot : snapshots) {
            Volume parent = dbClient.queryObject(Volume.class, snapshot.getParent().getURI());
            VolumeSnapshot driverSnapshot = new VolumeSnapshot();
            driverSnapshot.setParentId(parent.getNativeId());
            driverSnapshot.setStorageSystemId(storageSystemNativeId);
            driverSnapshot.setDisplayName(snapshot.getLabel());
            if (readOnly) {
                driverSnapshot.setAccessStatus(StorageObject.AccessStatus.READ_ONLY);
            } else {
                driverSnapshot.setAccessStatus(StorageObject.AccessStatus.READ_WRITE);
            }
            driverSnapshotToSnapshotMap.put(driverSnapshot, snapshot);
            driverSnapshots.add(driverSnapshot);
        }

        // Prepare driver consistency group of the parent volume
        VolumeConsistencyGroup driverCG = new VolumeConsistencyGroup();
        driverCG.setNativeId(consistencyGroup.getNativeId());
        driverCG.setDisplayName(consistencyGroup.getLabel());
        driverCG.setStorageSystemId(storageSystem.getNativeId());
        // call driver
        BlockStorageDriver driver = getDriver(storageSystem.getSystemType());
        DriverTask task = driver.createConsistencyGroupSnapshot(driverCG, Collections.unmodifiableList(driverSnapshots), null);
        // todo: need to implement support for async case.
        if (task.getStatus() == DriverTask.TaskStatus.READY) {
            // update snapshots
            for (VolumeSnapshot driverSnapshot : driverSnapshotToSnapshotMap.keySet()) {
                BlockSnapshot snapshot = driverSnapshotToSnapshotMap.get(driverSnapshot);
                snapshot.setNativeId(driverSnapshot.getNativeId());
                snapshot.setDeviceLabel(driverSnapshot.getDeviceLabel());
                snapshot.setIsSyncActive(true);
                // we use driver snapshot consistency group id as replication group label for group snapshots
                snapshot.setReplicationGroupInstance(driverSnapshot.getConsistencyGroup());
                if (driverSnapshot.getProvisionedCapacity() > 0) {
                    snapshot.setProvisionedCapacity(driverSnapshot.getProvisionedCapacity());
                }
                if (driverSnapshot.getAllocatedCapacity() > 0) {
                    snapshot.setAllocatedCapacity(driverSnapshot.getAllocatedCapacity());
                }
            }
            dbClient.updateObject(driverSnapshotToSnapshotMap.values());
            String msg = String.format("createGroupSnapshots -- Created snapshots: %s .", task.getMessage());
            _log.info(msg);
            taskCompleter.ready(dbClient);
        } else {
            for (BlockSnapshot snapshot : snapshots) {
                snapshot.setInactive(true);
            }
            dbClient.updateObject(snapshots);
            String errorMsg = String.format("doCreateSnapshot -- Failed to create snapshots: %s .", task.getMessage());
            _log.error(errorMsg);
            ServiceError serviceError = ExternalDeviceException.errors.createSnapshotsFailed("doCreateSnapshot", errorMsg);
            taskCompleter.error(dbClient, serviceError);
        }
    }

    private void deleteVolumeSnapshot(StorageSystem storageSystem, URI snapshot,
                                      TaskCompleter taskCompleter) {
        BlockSnapshot blockSnapshot = dbClient.queryObject(BlockSnapshot.class, snapshot);
        if (blockSnapshot != null && !blockSnapshot.getInactive() &&
                // If the blockSnapshot.nativeId is not filled in than the
                // snapshot create may have failed somehow, so we'll allow
                // this case to be marked as success, so that the inactive
                // state against the BlockSnapshot object can be set.
                !Strings.isNullOrEmpty(blockSnapshot.getNativeId())) {
            _log.info("Deleting snapshot of a volume. Snapshot: {}", snapshot);
            Volume parent = dbClient.queryObject(Volume.class, blockSnapshot.getParent().getURI());
            VolumeSnapshot driverSnapshot = new VolumeSnapshot();
            driverSnapshot.setStorageSystemId(storageSystem.getNativeId());
            driverSnapshot.setNativeId(blockSnapshot.getNativeId());
            driverSnapshot.setParentId(parent.getNativeId());
            driverSnapshot.setConsistencyGroup(blockSnapshot.getReplicationGroupInstance());
            List<VolumeSnapshot> driverSnapshots = new ArrayList<>();
            driverSnapshots.add(driverSnapshot);
            // call driver
            BlockStorageDriver driver = getDriver(storageSystem.getSystemType());
            DriverTask task = driver.deleteVolumeSnapshot(Collections.unmodifiableList(driverSnapshots));
            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                // update snapshots
                blockSnapshot.setInactive(true);
                dbClient.updateObject(blockSnapshot);
                String msg = String.format("deleteVolumeSnapshot -- Deleted snapshot: %s .", task.getMessage());
                _log.info(msg);
                taskCompleter.ready(dbClient);
            } else {
                String errorMsg = String.format("doDeleteSnapshot -- Failed to delete snapshot: %s .", task.getMessage());
                _log.error(errorMsg);
                ServiceError serviceError = ExternalDeviceException.errors.deleteSnapshotFailed("doDeleteSnapshot", errorMsg);
                taskCompleter.error(dbClient, serviceError);
            }
        } else if (blockSnapshot != null) {
            blockSnapshot.setInactive(true);
            dbClient.updateObject(blockSnapshot);
            String msg = String.format("deleteVolumeSnapshot -- Deleted snapshot: %s .", blockSnapshot.getId());
            _log.info(msg);
            taskCompleter.ready(dbClient);
        }
        taskCompleter.ready(dbClient);
    }

    private void deleteGroupSnapshots(StorageSystem storageSystem, List<BlockSnapshot> groupSnapshots,
                                      TaskCompleter taskCompleter) {
        _log.info("Deleting snapshot of consistency group. Snapshots: "+Joiner.on("\t").join(groupSnapshots));
        URI cgUri = groupSnapshots.get(0).getConsistencyGroup();
        BlockConsistencyGroup consistencyGroup = dbClient.queryObject(BlockConsistencyGroup.class, cgUri);
        List<VolumeSnapshot> driverSnapshots = new ArrayList<>();
        for (BlockSnapshot blockSnapshot : groupSnapshots) {
            VolumeSnapshot driverSnapshot = new VolumeSnapshot();
            driverSnapshot.setStorageSystemId(storageSystem.getNativeId());
            driverSnapshot.setNativeId(blockSnapshot.getNativeId());
            driverSnapshot.setConsistencyGroup(blockSnapshot.getReplicationGroupInstance());
            Volume parent = dbClient.queryObject(Volume.class, blockSnapshot.getParent().getURI());
            driverSnapshot.setParentId(parent.getNativeId());
            driverSnapshots.add(driverSnapshot);
        }
        // call driver
        BlockStorageDriver driver = getDriver(storageSystem.getSystemType());
        DriverTask task = driver.deleteConsistencyGroupSnapshot(Collections.unmodifiableList(driverSnapshots));
        // todo: need to implement support for async case.
        if (task.getStatus() == DriverTask.TaskStatus.READY) {
            // update snapshots
            for (BlockSnapshot blockSnapshot : groupSnapshots) {
                blockSnapshot.setInactive(true);
            }
            dbClient.updateObject(groupSnapshots);
            String msg = String.format("deleteGroupSnapshots -- Deleted group snapshot: %s .", task.getMessage());
            _log.info(msg);
            taskCompleter.ready(dbClient);
        } else {
            String errorMsg = String.format("doDeleteSnapshot -- Failed to delete group snapshot: %s .", task.getMessage());
            _log.error(errorMsg);
            ServiceError serviceError = ExternalDeviceException.errors.deleteGroupSnapshotFailed("doDeleteSnapshot", errorMsg);
            taskCompleter.error(dbClient, serviceError);
        }
    }

    private void updateConsistencyGroupsWithStorageSystem(Set<URI> consistencyGroups, StorageSystem storageSystem) {
        List<BlockConsistencyGroup> updateCGs = new ArrayList<>();
        Iterator<BlockConsistencyGroup> consistencyGroupIterator =
                dbClient.queryIterativeObjects(BlockConsistencyGroup.class, consistencyGroups, true);
        while (consistencyGroupIterator.hasNext()) {
            BlockConsistencyGroup consistencyGroup = consistencyGroupIterator.next();
            consistencyGroup.setStorageController(storageSystem.getId());
            consistencyGroup.addConsistencyGroupTypes(BlockConsistencyGroup.Types.LOCAL.name());
            consistencyGroup.addSystemConsistencyGroup(storageSystem.getId().toString(), consistencyGroup.getLabel());
            updateCGs.add(consistencyGroup);
        }
        dbClient.updateObject(updateCGs);
    }

    private Volume getSnapshotParentVolume(BlockSnapshot snapshot) {
        Volume sourceVolume = null;
        URI sourceVolURI = snapshot.getParent().getURI();
        if (!NullColumnValueGetter.isNullURI(sourceVolURI)) {
            sourceVolume = dbClient.queryObject(Volume.class, sourceVolURI);
        }
        return sourceVolume;
    }

    @Override
    public Map<String, Set<URI>> findExportMasks(StorageSystem storage,
                                                 List<String> initiatorNames, boolean mustHaveAllPorts) {
        return exportMaskOperationsHelper.findExportMasks(storage, initiatorNames, mustHaveAllPorts);
    }

    @Override
    public void doWaitForSynchronized(Class<? extends BlockObject> clazz, StorageSystem storageObj, URI target, TaskCompleter completer) {
        _log.info("No support for wait for synchronization for external devices.");
        completer.ready(dbClient);
    }

    @Override
    public void doWaitForGroupSynchronized(StorageSystem storageObj, List<URI> target, TaskCompleter completer)
    {
        _log.info("No support for wait for synchronization for external devices.");
        completer.ready(dbClient);

    }

}
