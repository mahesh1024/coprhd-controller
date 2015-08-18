/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.volumecontroller.impl.plugins;

import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.model.*;
import com.emc.storageos.db.client.model.DiscoveredDataObject.DiscoveryStatus;
import com.emc.storageos.db.client.util.CommonTransformerFunctions;
import com.emc.storageos.db.client.util.CustomQueryUtility;
import com.emc.storageos.plugins.AccessProfile;
import com.emc.storageos.plugins.BaseCollectionException;
import com.emc.storageos.plugins.StorageSystemViewObject;
import com.emc.storageos.scaleio.ScaleIOException;
import com.emc.storageos.scaleio.api.restapi.ScaleIORestClient;
import com.emc.storageos.scaleio.api.restapi.response.ScaleIOProtectionDomain;
import com.emc.storageos.scaleio.api.restapi.response.ScaleIOSDC;
import com.emc.storageos.scaleio.api.restapi.response.ScaleIOSDS;
import com.emc.storageos.scaleio.api.restapi.response.ScaleIOSDS.IP;
import com.emc.storageos.scaleio.api.restapi.response.ScaleIOStoragePool;
import com.emc.storageos.scaleio.api.restapi.response.ScaleIOSystem;
import com.emc.storageos.util.VersionChecker;
import com.emc.storageos.volumecontroller.impl.NativeGUIDGenerator;
import com.emc.storageos.volumecontroller.impl.StoragePoolAssociationHelper;
import com.emc.storageos.volumecontroller.impl.StoragePortAssociationHelper;
import com.emc.storageos.volumecontroller.impl.monitoring.cim.enums.OperationalStatus;
import com.emc.storageos.volumecontroller.impl.scaleio.ScaleIOHandleFactory;
import com.emc.storageos.volumecontroller.impl.utils.DiscoveryUtils;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.emc.storageos.db.client.util.CustomQueryUtility.queryActiveResourcesByAltId;
import static com.emc.storageos.volumecontroller.impl.NativeGUIDGenerator.generateNativeGuid;

public class ScaleIOCommunicationInterface extends ExtendedCommunicationInterfaceImpl {
    private static final Logger log = LoggerFactory.getLogger(ScaleIOCommunicationInterface.class);
    private static final int LOCK_WAIT_SECONDS = 300;
    private static final Set<String> SCALEIO_ONLY = Collections.singleton(HostInterface.Protocol.ScaleIO.name());
    private static final Set<String> SCALEIO_AND_ISCSI = new HashSet<>();

    private ScaleIOHandleFactory scaleIOHandleFactory;

    static {
        SCALEIO_AND_ISCSI.add(HostInterface.Protocol.ScaleIO.name());
        SCALEIO_AND_ISCSI.add(HostInterface.Protocol.iSCSI.name());
    }

    public void setScaleIOHandleFactory(ScaleIOHandleFactory scaleIOHandleFactory) {
        this.scaleIOHandleFactory = scaleIOHandleFactory;
        this.scaleIOHandleFactory.setDbClient(_dbClient);
    }

    @Override
    public void collectStatisticsInformation(AccessProfile accessProfile) throws BaseCollectionException {

    }

    @Override
    public void scan(AccessProfile accessProfile) throws BaseCollectionException {
        log.info("Starting scan of ScaleIO StorageProvider. IP={}", accessProfile.getIpAddress());
        StorageProvider.ConnectionStatus cxnStatus = StorageProvider.ConnectionStatus.CONNECTED;
        StorageProvider provider = _dbClient.queryObject(StorageProvider.class, accessProfile.getSystemId());
        _locker.acquireLock(accessProfile.getIpAddress(), LOCK_WAIT_SECONDS);
        try {
            ScaleIORestClient scaleIOHandle = scaleIOHandleFactory.using(_dbClient).getClientHandle(provider);
            if (scaleIOHandle != null) {
                Map<String, StorageSystemViewObject> storageSystemsCache = accessProfile.getCache();
                ScaleIOSystem sioSystem = scaleIOHandle.getSystem();
                String[] ipList = sioSystem.getSecondaryMdmActorIpList();
                if (ipList != null && ipList.length >0) {
                    StringSet secondaryIps = new StringSet();
                    secondaryIps.add(ipList[0]);
                    provider.setSecondaryIps(secondaryIps);
                }
                String scaleIOType = StorageSystem.Type.scaleio.name();
                String installationId = sioSystem.getInstallId();
                String version = sioSystem.getVersion().replaceAll("_", ".");
                String minimumSupported = VersionChecker.getMinimumSupportedVersion(StorageSystem.Type.scaleio);
                String compatibility = (VersionChecker.verifyVersionDetails(minimumSupported, version) < 0) ?
                        StorageSystem.CompatibilityStatus.INCOMPATIBLE.name() :
                        StorageSystem.CompatibilityStatus.COMPATIBLE.name();
                provider.setCompatibilityStatus(compatibility);
                provider.setVersionString(version);
                List<ScaleIOProtectionDomain> protectionDomains = scaleIOHandle.getProtectionDomains();
                for (ScaleIOProtectionDomain protectionDomain : protectionDomains) {
                    log.info("For ScaleIO instance {}, found ProtectionDomain {}", installationId, protectionDomain.getName());
                    String id = String.format("%s+%s", installationId, protectionDomain.getName());
                    String nativeGuid = generateNativeGuid(scaleIOType, id);
                    StorageSystemViewObject viewObject = storageSystemsCache.get(nativeGuid);
                    if (viewObject == null) {
                        viewObject = new StorageSystemViewObject();
                    }
                    viewObject.setDeviceType(scaleIOType);
                    viewObject.addprovider(accessProfile.getSystemId().toString());
                    viewObject.setProperty(StorageSystemViewObject.MODEL, "ScaleIO ECS");
                    viewObject.setProperty(StorageSystemViewObject.SERIAL_NUMBER, id);
                    storageSystemsCache.put(nativeGuid, viewObject);
                }
            }
        } catch (Exception e) {
            cxnStatus = StorageProvider.ConnectionStatus.NOTCONNECTED;
            log.error(String.format("Exception was encountered when attempting to scan ScaleIO Instance %s",
                    accessProfile.getIpAddress()), e);
            throw ScaleIOException.exceptions.scanFailed(e);
        } finally {
            provider.setConnectionStatus(cxnStatus.name());
            _dbClient.persistObject(provider);
            log.info("Completed scan of ScaleIO StorageProvider. IP={}", accessProfile.getIpAddress());
            _locker.releaseLock(accessProfile.getIpAddress());
        }
    }

    @Override
    public void discover(AccessProfile accessProfile) throws BaseCollectionException {
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, accessProfile.getSystemId());
        _locker.acquireLock(accessProfile.getIpAddress(), LOCK_WAIT_SECONDS);
        log.info("Starting discovery of ScaleIO StorageProvider. IP={} StorageSystem {}",
                accessProfile.getIpAddress(), storageSystem.getNativeGuid());
        try {
            ScaleIORestClient scaleIOHandle = scaleIOHandleFactory.using(_dbClient).getClientHandle(storageSystem);
            if (scaleIOHandle != null) {
                ScaleIOSystem sioSystem = scaleIOHandle.getSystem();
                List<ScaleIOProtectionDomain> protectionDomains = scaleIOHandle.getProtectionDomains();
                List<ScaleIOSDC> allSDCs = scaleIOHandle.queryAllSDC();
                List<ScaleIOSDS> allSDSs = scaleIOHandle.queryAllSDS();
                
                List<StoragePort> ports = new ArrayList<>();
                List<StoragePool> newPools = new ArrayList<StoragePool>();
                List<StoragePool> updatePools = new ArrayList<StoragePool>();
                List<StoragePool> allPools = new ArrayList<StoragePool>();
                String scaleIOType = StorageSystem.Type.scaleio.name();
                String installationId = sioSystem.getInstallId();
                String version = sioSystem.getVersion().replaceAll("_", ".");
                String minimumSupported = VersionChecker.getMinimumSupportedVersion(StorageSystem.Type.scaleio);
                String compatibility = (VersionChecker.verifyVersionDetails(minimumSupported, version) < 0) ?
                        StorageSystem.CompatibilityStatus.INCOMPATIBLE.name() :
                        StorageSystem.CompatibilityStatus.COMPATIBLE.name();
                storageSystem.setFirmwareVersion(version);
                storageSystem.setCompatibilityStatus(compatibility);
                storageSystem.setReachableStatus(true);
                storageSystem.setLabel(storageSystem.getNativeGuid());

                for (ScaleIOProtectionDomain protectionDomain : protectionDomains) {
                    String domainName = protectionDomain.getName();
                    String id = String.format("%s+%s", installationId, domainName);
                    String storageSystemNativeGUID = generateNativeGuid(scaleIOType, id);
                    if (!storageSystemNativeGUID.equals(storageSystem.getNativeGuid())) {
                        // This is not the ProtectionDomain that we're looking for
                        continue;
                    }
                   
                    String protectionDomainId = protectionDomain.getId();
                    storageSystem.setSerialNumber(protectionDomainId);
                   
                    Network network = createNetwork(installationId);
                    List<ScaleIOSDS> sdsList = new ArrayList<ScaleIOSDS>();
                    for (ScaleIOSDS sds : allSDSs) {
                        String pdId = sds.getProtectionDomainId();
                        if (pdId.equals(protectionDomainId)) {
                            sdsList.add(sds);
                        }
                    }
                    List<StoragePort> thesePorts =
                            createStoragePorts(storageSystem, compatibility, network, sdsList, domainName);
                    ports.addAll(thesePorts);
                    createHost(network, allSDCs);
                    
                    List<StoragePort> notVisiblePorts = DiscoveryUtils.checkStoragePortsNotVisible(ports, _dbClient,
                            storageSystem.getId());
                    if (notVisiblePorts != null && !notVisiblePorts.isEmpty()) {
                        ports.addAll(notVisiblePorts);
                    }
                    Set<String> supportedProtocols = SCALEIO_ONLY;
                    List<ScaleIOStoragePool> storagePools = scaleIOHandle.getProtectionDomainStoragePools(protectionDomainId);
                    for (ScaleIOStoragePool storagePool : storagePools) {
                        String poolName = storagePool.getName();
                        String nativeGuid = String.format("%s-%s-%s", installationId, protectionDomain, poolName);
                        log.info("Attempting to discover pool {} for ProtectionDomain {}", poolName, domainName);
                        List<StoragePool> pools =
                                queryActiveResourcesByAltId(_dbClient, StoragePool.class, "nativeGuid", nativeGuid);
                        StoragePool pool = null;
                        if (pools.isEmpty()) {
                            log.info("Pool {} is new", poolName);
                            pool = new StoragePool();
                            pool.setId(URIUtil.createId(StoragePool.class));
                            pool.setStorageDevice(accessProfile.getSystemId());
                            pool.setPoolServiceType(StoragePool.PoolServiceType.block.toString());
                            pool.setOperationalStatus(StoragePool.PoolOperationalStatus.READY.name());
                            pool.setCompatibilityStatus(compatibility);
                            pool.setThinVolumePreAllocationSupported(false);
                            pool.addDriveTypes(Collections.singleton(StoragePool.SupportedDriveTypeValues.SATA.name()));
                            StringSet copyTypes = new StringSet();
                            copyTypes.add(StoragePool.CopyTypes.ASYNC.name());
                            copyTypes.add(StoragePool.CopyTypes.UNSYNC_UNASSOC.name());
                            pool.setSupportedCopyTypes(copyTypes);
                            pool.setMaximumThickVolumeSize(1048576L);
                            pool.setMinimumThickVolumeSize(1L);
                            newPools.add(pool);
                        } else if (pools.size() == 1) {
                            log.info("Pool {} was previously discovered", storagePool);
                            pool = pools.get(0);
                            updatePools.add(pool);
                        } else {
                            log.warn(String.format("There are %d StoragePools with nativeGuid = %s", pools.size(),
                                    nativeGuid));
                            continue;
                        }
                        pool.setPoolName(poolName);
                        pool.setNativeId(storagePool.getId());
                        pool.setNativeGuid(nativeGuid);
                        String availableCapacityString = storagePool.getCapacityAvailableForVolumeAllocationInKb();
                        pool.setFreeCapacity(Long.parseLong(availableCapacityString));
                        String totalCapacityString = storagePool.getMaxCapacityInKb();
                        pool.setTotalCapacity(Long.parseLong(totalCapacityString));
                        pool.addProtocols(supportedProtocols);

                        pool.setSupportedResourceTypes(StoragePool.SupportedResourceTypes.THIN_AND_THICK.name());
                        Long maxThinSize = 1048576L;
                        Long minThinSize = 1L;
                        pool.setMaximumThinVolumeSize(maxThinSize);
                        pool.setMinimumThinVolumeSize(minThinSize);
                        pool.setInactive(false);
                        pool.setDiscoveryStatus(DiscoveryStatus.VISIBLE.name());

                    }
                }
                log.info(String.format("For StorageSystem %s, discovered %d new pools and %d pools to update",
                        storageSystem.getNativeGuid(), newPools.size(), updatePools.size()));
                StoragePoolAssociationHelper.setStoragePoolVarrays(storageSystem.getId(), newPools, _dbClient);
                allPools.addAll(newPools);
                allPools.addAll(updatePools);
                _dbClient.createObject(newPools);
                _dbClient.updateAndReindexObject(updatePools);
                List<StoragePool> notVisiblePools = DiscoveryUtils.checkStoragePoolsNotVisible(allPools, _dbClient,
                        storageSystem.getId());
                if (notVisiblePools != null && !notVisiblePools.isEmpty()) {
                    allPools.addAll(notVisiblePools);
                }
                StoragePortAssociationHelper.runUpdatePortAssociationsProcess(ports, null, _dbClient, _coordinator, allPools);
            }
        } catch (Exception e) {
            storageSystem.setReachableStatus(false);
            log.error(String.format("Exception was encountered when attempting to discover ScaleIO Instance %s",
                    accessProfile.getIpAddress()), e);
        } finally {
            _locker.releaseLock(accessProfile.getIpAddress());
        }
        _dbClient.updateAndReindexObject(storageSystem);
        log.info("Completed of ScaleIO StorageProvider. IP={} StorageSystem {}",
                accessProfile.getIpAddress(), storageSystem.getNativeGuid());
    }


    /**
     * Create a Host object for every SDC that is found on the system. Create a single
     * initiator for the host in the specified Network.
     * 
     * @param network [in] Network object to associated the hosts' initiator ports
     * @param queryAllSDCResult [in] - SDC query result
     */
    private void createHost(Network network, List<ScaleIOSDC> allSDCs) {
        // Find the root tenant and associate any SDC hosts with it
        List<URI> tenantOrgList = _dbClient.queryByType(TenantOrg.class, true);
        Iterator<TenantOrg> it = _dbClient.queryIterativeObjects(TenantOrg.class, tenantOrgList);
        List<String> initiatorsToAddToNetwork = new ArrayList<>();
        URI rootTenant = null;
        while (it.hasNext()) {
            TenantOrg tenantOrg = it.next();
            if (TenantOrg.isRootTenant(tenantOrg)) {
                rootTenant = tenantOrg.getId();
                break;
            }
        }
        for (ScaleIOSDC sdc : allSDCs) {
            String ip = sdc.getSdcIp();
            String guid = sdc.getSdcGuid();

            // First we search by nativeGuid
            Host host = findByNativeGuid(guid);

            if (host == null) {
                // Host with nativeGuid is not known to ViPR, try and find by IP address
                host = findOrCreateByIp(rootTenant, ip, guid);
            }

            // Create an single initiator for this SDC. If the initiator has already been
            // created, the existing Initiator will be returned. Associate the initiator
            // with the network
            Initiator initiator = createInitiator(host, ip, sdc.getId());
            if (!network.hasEndpoint(initiator.getInitiatorPort())) {
                initiatorsToAddToNetwork.add(initiator.getInitiatorPort());
            }
        }

        if (!initiatorsToAddToNetwork.isEmpty()) {
            network.addEndpoints(initiatorsToAddToNetwork, true);
            _dbClient.updateAndReindexObject(network);
        }
    }

    private Host findByNativeGuid(String guid) {
        List<Host> resultsByGuid =
                CustomQueryUtility.
                        queryActiveResourcesByAltId(_dbClient, Host.class, "nativeGuid", guid);

        if (resultsByGuid == null || resultsByGuid.isEmpty()) {
            return null;
        }
        return resultsByGuid.get(0);
    }

    private Host findOrCreateByIp(URI rootTenant, String ip, String guid) {
        List<IpInterface> resultsByIp =
                CustomQueryUtility.
                        queryActiveResourcesByAltId(_dbClient, IpInterface.class, "ipAddress", ip);
        Host host = null;

        if (resultsByIp == null || resultsByIp.isEmpty()) {
            host = findHostByHostName(ip);
            if (host == null) {
                log.info(String.format("Could not find any existing Host with IpInterface or Hostname %s. " +
                        "Creating new Host for SDC %s", ip, guid));
                // Host with IP address not found, need to create it
                host = new Host();
                host.setId(URIUtil.createId(Host.class));
                host.setHostName(ip);
                host.setTenant(rootTenant);
                host.setType(DiscoveredDataObject.Type.host.name());
                host.setLabel(ip);
                host.setNativeGuid(guid);
                host.setType(Host.HostType.Other.name());
                host.setDiscoverable(false);
                host.setDiscoveryStatus(DiscoveredDataObject.DataCollectionJobStatus.COMPLETE.name());
                host.setCompatibilityStatus(DiscoveredDataObject.CompatibilityStatus.COMPATIBLE.name());
                host.setSuccessDiscoveryTime(System.currentTimeMillis());
                host.setInactive(false);
                _dbClient.createObject(host);
            }
        } else {
            // Host with IP address was found, need to update it with nativeGuid unless it's already set.
            IpInterface ipInterface = resultsByIp.get(0);
            host = _dbClient.queryObject(Host.class, ipInterface.getHost());

            if (host != null && Strings.isNullOrEmpty(host.getNativeGuid())) {
                log.info(String.format("Existing Host %s (%s) found and will be used as container for SDC %s",
                        ip, host.getNativeGuid(), guid));
                host.setNativeGuid(guid);
                _dbClient.updateAndReindexObject(host);
            } else {
                log.warn(String.format("Found an IpInterface %s, but its associated Host %s could not be found.",
                        ip, ipInterface.getHost()));
            }
        }

        return host;
    }

    private Host findHostByHostName(String ip) {
        Host host = null;
        List<Host> results =
                CustomQueryUtility.
                        queryActiveResourcesByAltId(_dbClient, Host.class, "hostName", ip);
        if (results != null && !results.isEmpty()) {
            Collection<URI> hostIdStrings = Collections2.transform(results,
                    CommonTransformerFunctions.fctnDataObjectToID());
            log.info(String.format("Found %d hosts with ip %s as their hostName: %s",
                    results.size(), ip, CommonTransformerFunctions.collectionToString(hostIdStrings)));
            host = results.get(0);
        }
        return host;
    }

    /**
     * Create initiator for the specified host
     * 
     * @param host [in] - Host object reference
     * @param ip [in] - IP address string
     * @param id [id] - Indentifier for the port
     * @return Initiator object
     */
    private Initiator createInitiator(Host host, String ip, String id) {
        Initiator initiator;
        List<Initiator> results =
                CustomQueryUtility.queryActiveResourcesByAltId(_dbClient, Initiator.class, "iniport", id);
        if (results == null || results.isEmpty()) {
            initiator = new Initiator();
            initiator.setId(URIUtil.createId(Initiator.class));
            initiator.setHost(host.getId());
            initiator.setHostName(ip);
            initiator.setInitiatorPort(id);
            initiator.setProtocol(HostInterface.Protocol.ScaleIO.name());
            initiator.setRegistrationStatus(DiscoveredDataObject.RegistrationStatus.REGISTERED.name());
            initiator.setInactive(false);
            _dbClient.createObject(initiator);
        } else {
            initiator = results.get(0);
        }
        return initiator;
    }

    /**
     * Create a Network object for the ScaleIO instance and associate it with the VArray
     * 
     * @param uniqueId [in] - Unique string identifier for the network
     * @return Network object
     */
    private Network createNetwork(String uniqueId) {
        Network network;
        List<Network> results =
                CustomQueryUtility.queryActiveResourcesByAltId(_dbClient, Network.class, "nativeId", uniqueId);
        if (results == null || results.isEmpty()) {
            network = new Network();
            network.setId(URIUtil.createId(Network.class));
            network.setTransportType(StorageProtocol.Transport.ScaleIO.name());
            network.setNativeId(uniqueId);
            network.setLabel(String.format("%s-ScaleIONetwork", uniqueId));
            network.setRegistrationStatus(DiscoveredDataObject.RegistrationStatus.REGISTERED.name());
            network.setInactive(false);
            _dbClient.createObject(network);
        } else {
            network = results.get(0);
        }
        return network;
    }

    /**
     * Create a StoragePort for each SDS in the ScaleIO instance. These are psuedo-StoragePorts
     * for the purpose of tying up the host-end to the storage-end of the Network
     * 
     * @param storageSystem [in] - StorageSystem object (ProtectionDomain)
     * @param compatibilityStatus [in] - Compatibility status to use on the ports
     * @param network [in] - Network to associate with the ports
     * @param queryAllSDSResult [in] - SDS query result
     * @param protectionDomainName [in] - Protection Domain name
     */
    private List<StoragePort> createStoragePorts(StorageSystem storageSystem, String compatibilityStatus, Network network,
            List<ScaleIOSDS> allSDSs, String protectionDomainName) throws IOException {
        List<StoragePort> ports = new ArrayList<>();
        List<String> endpoints = new ArrayList<>();
        //String id = protectionDomain.getId();
        for (ScaleIOSDS sds : allSDSs) {
            String sdsId = sds.getId();
            List<IP> ips = sds.getIpList();
            String sdsIP = null;
            if (ips != null && !ips.isEmpty()) {
                sdsIP = ips.get(0).getIp();
            }
            
            StoragePort port;
            List<StoragePort> results = CustomQueryUtility.
                    queryActiveResourcesByAltId(_dbClient, StoragePort.class, "portNetworkId", sdsId);
            if (results == null || results.isEmpty()) {
                String nativeGUID =
                        NativeGUIDGenerator.generateNativeGuid(storageSystem,
                                protectionDomainName, NativeGUIDGenerator.ADAPTER);
                StorageHADomain adapter = new StorageHADomain();
                adapter.setStorageDeviceURI(storageSystem.getId());
                adapter.setId(URIUtil.createId(StorageHADomain.class));
                adapter.setAdapterName(protectionDomainName);
                adapter.setLabel(protectionDomainName);
                adapter.setNativeGuid(nativeGUID);
                adapter.setNumberofPorts("1");
                adapter.setAdapterType(StorageHADomain.HADomainType.FRONTEND.name());
                adapter.setInactive(false);
                _dbClient.createObject(adapter);

                port = new StoragePort();
                port.setId(URIUtil.createId(StoragePort.class));
                port.setPortNetworkId(sdsId);
                port.setLabel(String.format("%s-%s-StoragePort", protectionDomainName, sdsId));
                port.setStorageDevice(storageSystem.getId());
                port.setCompatibilityStatus(compatibilityStatus);
                port.setOperationalStatus(OperationalStatus.OK.name());
                port.setIpAddress(sdsIP);
                port.setNetwork(network.getId());
                port.setPortGroup(sdsId);
                port.setPortName(sdsId);
                port.setPortType(StoragePort.PortType.frontend.name());
                port.setStorageHADomain(adapter.getId());
                port.setTransportType(StorageProtocol.Transport.ScaleIO.name());
                port.setDiscoveryStatus(DiscoveryStatus.VISIBLE.name());
                port.setInactive(false);
                _dbClient.createObject(port);
                endpoints.add(port.getPortNetworkId());
            } else {
                port = results.get(0);
            }
            ports.add(port);
        }
        network.addEndpoints(endpoints, true);
        _dbClient.updateAndReindexObject(network);
        return ports;
    }

}
