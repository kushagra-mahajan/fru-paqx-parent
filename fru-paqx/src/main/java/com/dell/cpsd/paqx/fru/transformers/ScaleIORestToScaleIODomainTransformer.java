package com.dell.cpsd.paqx.fru.transformers;

import com.dell.cpsd.paqx.fru.domain.ScaleIOData;
import com.dell.cpsd.paqx.fru.domain.ScaleIODevice;
import com.dell.cpsd.paqx.fru.domain.ScaleIOFaultSet;
import com.dell.cpsd.paqx.fru.domain.ScaleIOIP;
import com.dell.cpsd.paqx.fru.domain.ScaleIOMasterElementInfo;
import com.dell.cpsd.paqx.fru.domain.ScaleIOMasterScaleIOIP;
import com.dell.cpsd.paqx.fru.domain.ScaleIOMdmCluster;
import com.dell.cpsd.paqx.fru.domain.ScaleIOPrimaryMDMIP;
import com.dell.cpsd.paqx.fru.domain.ScaleIOProtectionDomain;
import com.dell.cpsd.paqx.fru.domain.ScaleIOSDC;
import com.dell.cpsd.paqx.fru.domain.ScaleIOSDS;
import com.dell.cpsd.paqx.fru.domain.ScaleIOSDSElementInfo;
import com.dell.cpsd.paqx.fru.domain.ScaleIOSecondaryMDMIP;
import com.dell.cpsd.paqx.fru.domain.ScaleIOSlaveElementInfo;
import com.dell.cpsd.paqx.fru.domain.ScaleIOStoragePool;
import com.dell.cpsd.paqx.fru.domain.ScaleIOTiebreakerElementInfo;
import com.dell.cpsd.paqx.fru.domain.ScaleIOTiebreakerScaleIOIP;
import com.dell.cpsd.storage.capabilities.api.MasterDataRestRep;
import com.dell.cpsd.storage.capabilities.api.MdmClusterDataRestRep;
import com.dell.cpsd.storage.capabilities.api.ScaleIODeviceDataRestRep;
import com.dell.cpsd.storage.capabilities.api.ScaleIOFaultSetDataRestRep;
import com.dell.cpsd.storage.capabilities.api.ScaleIOProtectionDomainDataRestRep;
import com.dell.cpsd.storage.capabilities.api.ScaleIOSDCDataRestRep;
import com.dell.cpsd.storage.capabilities.api.ScaleIOSDSDataRestRep;
import com.dell.cpsd.storage.capabilities.api.ScaleIOStoragePoolDataRestRep;
import com.dell.cpsd.storage.capabilities.api.ScaleIOSystemDataRestRep;
import com.dell.cpsd.storage.capabilities.api.SlavesDataRestRep;
import com.dell.cpsd.storage.capabilities.api.TieBreakersDataRestRep;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Created by kenefj on 10/05/2017.
 */
public class ScaleIORestToScaleIODomainTransformer
{
    public ScaleIOData transform(final ScaleIOSystemDataRestRep scaleIOSystemDataRestRep)
    {
        //Create the scaleIODataObject
        ScaleIOData returnVal = new ScaleIOData(scaleIOSystemDataRestRep.getId(), scaleIOSystemDataRestRep.getName(),
                scaleIOSystemDataRestRep.getInstallId(), scaleIOSystemDataRestRep.getMdmMode(),
                scaleIOSystemDataRestRep.getSystemVersionName(), scaleIOSystemDataRestRep.getMdmClusterState(),
                scaleIOSystemDataRestRep.getVersion());

        //Link IP lists
        linkTieBreakerMdmIPList(returnVal, scaleIOSystemDataRestRep);
        linkPrimaryMdmIPList(returnVal, scaleIOSystemDataRestRep);
        linkSecondaryMdmIPList(returnVal, scaleIOSystemDataRestRep);

        //Link SDCs
        linkSDCs(returnVal, scaleIOSystemDataRestRep);

        //Link MDMCluster
        linkMDMCluster(returnVal, scaleIOSystemDataRestRep);

        //Find protection domains and fault sets
        Set<ScaleIOProtectionDomain> protectionDomains = findProtectionDomainsFromSDS(scaleIOSystemDataRestRep.getSdsList());
        linkProtectionDomainsToData(protectionDomains, returnVal);

        Set<ScaleIOFaultSet> faultSets = findFaultSetsFromSDS(scaleIOSystemDataRestRep.getSdsList());
        Set<ScaleIOStoragePool> storagePools = findStoragePoolsFromSDS(scaleIOSystemDataRestRep.getSdsList());
        //Each of these will now be linked as we go through the SDS's

        //Iterate through SDS
        linkSDSs(returnVal, scaleIOSystemDataRestRep, protectionDomains, faultSets, storagePools);

        //FINALLY
        return returnVal;
    }

    private void linkSDSs(final ScaleIOData returnVal, final ScaleIOSystemDataRestRep scaleIOSystemDataRestRep,
            final Set<ScaleIOProtectionDomain> protectionDomains, final Set<ScaleIOFaultSet> faultSets,
            final Set<ScaleIOStoragePool> storagePools)
    {
        for (ScaleIOSDSDataRestRep sds : scaleIOSystemDataRestRep.getSdsList())
        {
            ScaleIOSDS domainSDS = new ScaleIOSDS(sds.getId(), sds.getName(), sds.getSdsState(), Integer.valueOf(sds.getPort()));
            domainSDS.setScaleIOData(returnVal);
            returnVal.addSds(domainSDS);

            linkSDSToProtectionDomain(domainSDS, protectionDomains, sds.getProtectionDomainId());
            linkSDSToFaultSet(domainSDS, faultSets, sds.getFaultSetId());

            //linkProtectionDomainToFaultSet
            linkProtectionDomainToFaultSet(protectionDomains, faultSets, sds.getProtectionDomainId(), sds.getFaultSetId());

            linkSDSToDevices(domainSDS, sds, storagePools, protectionDomains);

        }

    }

    private void linkProtectionDomainToFaultSet(final Set<ScaleIOProtectionDomain> protectionDomains, final Set<ScaleIOFaultSet> faultSets,
            final String protectionDomainId, final String faultSetId)
    {
        ScaleIOProtectionDomain pd = protectionDomains.stream().filter(x -> x.getId().equals(protectionDomainId)).findFirst().orElse(null);
        ScaleIOFaultSet fs = faultSets.stream().filter(x -> x.getId().equals(faultSetId)).findFirst().orElse(null);

        if (pd != null && fs != null)
        {
            fs.setProtectionDomain(pd);
            pd.addFaultSet(fs);
        }
    }

    private void linkSDSToDevices(final ScaleIOSDS domainSDS, final ScaleIOSDSDataRestRep sds, final Set<ScaleIOStoragePool> storagePools,
            final Set<ScaleIOProtectionDomain> protectionDomains)
    {
        for (ScaleIODeviceDataRestRep device : sds.getDeviceList())
        {
            ScaleIODevice domainDevice = new ScaleIODevice(device.getId(), device.getName(), device.getDeviceCurrentPathName());
            domainDevice.setSds(domainSDS);
            domainSDS.addDevice(domainDevice);

            linkStoragePoolToDevice(domainDevice, device.getStoragePoolId(), storagePools);
            linkStoragePoolToProtectionDomain(storagePools, device.getStoragePoolId(), protectionDomains,
                    device.getScaleIOStoragePoolDataRestRep().getProtectionDomainId());
        }
    }

    private void linkStoragePoolToProtectionDomain(final Set<ScaleIOStoragePool> storagePools, final String storagePoolId,
            final Set<ScaleIOProtectionDomain> protectionDomains, final String protectionDomainId)
    {
        ScaleIOStoragePool storagePool = storagePools.stream().filter(x -> x.getId().equals(storagePoolId)).findFirst().orElse(null);
        ScaleIOProtectionDomain pd = protectionDomains.stream().filter(x -> x.getId().equals(protectionDomainId)).findFirst().orElse(null);

        if (pd != null && storagePool != null)
        {
            storagePool.setProtectionDomain(pd);
            pd.addStoragePool(storagePool);
        }
    }

    private void linkStoragePoolToDevice(final ScaleIODevice domainDevice, final String storagePoolId, Set<ScaleIOStoragePool> storagePools)
    {
        ScaleIOStoragePool storagePool = storagePools.stream().filter(x -> x.getId().equals(storagePoolId)).findFirst().orElse(null);
        if (storagePool != null)
        {
            storagePool.addDevice(domainDevice);
            domainDevice.setStoragePool(storagePool);
        }
    }

    private void linkSDSToFaultSet(final ScaleIOSDS domainSDS, final Set<ScaleIOFaultSet> faultSets, final String faultSetId)
    {
        ScaleIOFaultSet fs = faultSets.stream().filter(x -> x.getId().equals(faultSetId)).findFirst().orElse(null);

        if (fs != null)
        {
            domainSDS.setFaultSet(fs);
            fs.addSDS(domainSDS);
        }
    }

    private void linkSDSToProtectionDomain(final ScaleIOSDS domainSDS, final Set<ScaleIOProtectionDomain> protectionDomains,
            final String protectionDomainId)
    {
        ScaleIOProtectionDomain pd = protectionDomains.stream().filter(x -> x.getId().equals(protectionDomainId)).findFirst().orElse(null);

        if (pd != null)
        {
            domainSDS.setProtectionDomain(pd);
            pd.addSDS(domainSDS);
        }
    }

    private void linkProtectionDomainsToData(final Set<ScaleIOProtectionDomain> protectionDomains, final ScaleIOData returnVal)
    {
        for (ScaleIOProtectionDomain domain : protectionDomains)
        {
            domain.setScaleIOData(returnVal);
            returnVal.addProtectionDomain(domain);
        }
    }

    private Set<ScaleIOStoragePool> findStoragePoolsFromSDS(final List<ScaleIOSDSDataRestRep> sdsList)
    {
        return sdsList.stream().map(x -> x.getDeviceList()).flatMap(List::stream)
                .map(y -> createStoragePoolFromRest(y.getScaleIOStoragePoolDataRestRep())).collect(Collectors.toSet());
    }

    private ScaleIOStoragePool createStoragePoolFromRest(final ScaleIOStoragePoolDataRestRep scaleIOStoragePoolDataRestRep)
    {
        return new ScaleIOStoragePool(scaleIOStoragePoolDataRestRep.getId(), scaleIOStoragePoolDataRestRep.getName(),
                Integer.valueOf(scaleIOStoragePoolDataRestRep.getCapacityAvailableForVolumeAllocationInKb()),
                Integer.valueOf(scaleIOStoragePoolDataRestRep.getMaxCapacityInKb()),
                Integer.valueOf(scaleIOStoragePoolDataRestRep.getNumOfVolumes()));
    }

    private Set<ScaleIOProtectionDomain> findProtectionDomainsFromSDS(final List<ScaleIOSDSDataRestRep> sdsList)
    {

        return sdsList.stream().map(x -> x.getScaleIOProtectionDomainDataRestRep()).map(y -> createProtectionDomainFromRest(y))
                .collect(Collectors.toSet());
    }

    private Set<ScaleIOFaultSet> findFaultSetsFromSDS(final List<ScaleIOSDSDataRestRep> sdsList)
    {

        return sdsList.stream().map(x -> x.getScaleIOFaultSetDataRestRep()).map(y -> createFaultSetFromRest(y)).collect(Collectors.toSet());
    }

    private ScaleIOFaultSet createFaultSetFromRest(final ScaleIOFaultSetDataRestRep y)
    {
        return new ScaleIOFaultSet(y.getId(), y.getName());
    }

    private ScaleIOProtectionDomain createProtectionDomainFromRest(final ScaleIOProtectionDomainDataRestRep y)
    {
        return new ScaleIOProtectionDomain(y.getId(), y.getName(), y.getProtectionDomainState());
    }

    private void linkMDMCluster(final ScaleIOData returnVal, final ScaleIOSystemDataRestRep scaleIOSystemDataRestRep)
    {
        final MdmClusterDataRestRep cluster = scaleIOSystemDataRestRep.getMdmClusterDataRestRep();
        ScaleIOMdmCluster domainCluster = new ScaleIOMdmCluster(cluster.getId(), cluster.getName(), cluster.getClusterState(),
                cluster.getClusterMode(), Integer.getInteger(cluster.getGoodNodesNum()), Integer.valueOf(cluster.getGoodReplicasNum()));
        domainCluster.setScaleIOData(returnVal);
        returnVal.setMdmCluster(domainCluster);

        //Now need to add master, slave and tiebreaker
        addMasterToCluster(domainCluster, cluster.getMasterDataRestRep());
        addSlavesToCluster(domainCluster, cluster.getSlaves());
        addTiebreakersToCluster(domainCluster, cluster.getTieBreakers());
    }

    private void addTiebreakersToCluster(final ScaleIOMdmCluster domainCluster, final List<TieBreakersDataRestRep> tieBreakers)
    {
        for (TieBreakersDataRestRep tiebreaker : tieBreakers)
        {
            addTiebreakerToCluster(domainCluster, tiebreaker);
        }
    }

    private void addTiebreakerToCluster(final ScaleIOMdmCluster domainCluster, final TieBreakersDataRestRep tiebreaker)
    {
        ScaleIOTiebreakerElementInfo domainTiebreaker = new ScaleIOTiebreakerElementInfo(tiebreaker.getId(),
                Integer.valueOf(tiebreaker.getPort()), tiebreaker.getVersionInfo(), tiebreaker.getName(), tiebreaker.getRole(),
                tiebreaker.getStatus());
        domainCluster.addTiebreaker(domainTiebreaker);
        domainTiebreaker.setMdmCluster(domainCluster);
        //Now add ips and management ips
        addIPs(domainTiebreaker, tiebreaker.getIps());
        addManagementIPs(domainTiebreaker, tiebreaker.getManagementIPs());
    }

    private void addSlavesToCluster(final ScaleIOMdmCluster domainCluster, final List<SlavesDataRestRep> slaves)
    {
        for (SlavesDataRestRep slave : slaves)
        {
            addSlaveToCluster(domainCluster, slave);
        }
    }

    private void addSlaveToCluster(final ScaleIOMdmCluster domainCluster, final SlavesDataRestRep slave)
    {
        ScaleIOSlaveElementInfo domainSlave = new ScaleIOSlaveElementInfo(slave.getId(), Integer.valueOf(slave.getPort()),
                slave.getVersionInfo(), slave.getName(), slave.getRole(), slave.getStatus());
        domainCluster.addSlave(domainSlave);
        domainSlave.setMdmCluster(domainCluster);
        //Now add ips and management ips
        addIPs(domainSlave, slave.getIps());
        addManagementIPs(domainSlave, slave.getManagementIPs());
    }

    private void addMasterToCluster(final ScaleIOMdmCluster domainCluster, final MasterDataRestRep masterDataRestRep)
    {
        ScaleIOMasterElementInfo master = new ScaleIOMasterElementInfo(masterDataRestRep.getId(),
                Integer.valueOf(masterDataRestRep.getPort()), masterDataRestRep.getVersionInfo(), masterDataRestRep.getName(),
                masterDataRestRep.getRole());
        domainCluster.addMaster(master);
        master.setMdmCluster(domainCluster);
        //Now add ips and management ips
        addIPs(master, masterDataRestRep.getIps());
        addManagementIPs(master, masterDataRestRep.getManagementIPs());
    }

    private void addIPs(final ScaleIOSDSElementInfo master, final List<String> ips)
    {
        for (String ip : ips)
        {
            ScaleIOMasterScaleIOIP domainIP = new ScaleIOMasterScaleIOIP(master.getId(), ip);
            domainIP.setScaleIOSDSElementInfo(master);
            master.addIP(domainIP);
        }
    }

    private void addManagementIPs(final ScaleIOSDSElementInfo master, final List<String> ips)
    {
        for (String ip : ips)
        {
            ScaleIOMasterScaleIOIP domainIP = new ScaleIOMasterScaleIOIP(master.getId(), ip);
            domainIP.setScaleIOSDSElementInfo(master);
            master.addManagementIP(domainIP);
        }
    }

    private void linkSDCs(final ScaleIOData returnVal, final ScaleIOSystemDataRestRep scaleIOSystemDataRestRep)
    {
        for (ScaleIOSDCDataRestRep sdc : scaleIOSystemDataRestRep.getSdcList())
        {
            ScaleIOSDC domainSDC = new ScaleIOSDC(sdc.getId(), sdc.getName(), sdc.getSdcIp(), sdc.getSdcGuid(),
                    sdc.getMdmConnectionState());
            domainSDC.setScaleIOData(returnVal);
            returnVal.addSdc(domainSDC);
        }
    }

    private void linkSecondaryMdmIPList(final ScaleIOData returnVal, final ScaleIOSystemDataRestRep scaleIOSystemDataRestRep)
    {
        //Link secondaryMdmActorIpList
        List<ScaleIOIP> secondaryMdmActorIps = new ArrayList<>();
        for (String ip : scaleIOSystemDataRestRep.getSecondaryMdmActorIpList())
        {
            ScaleIOIP domainIP = new ScaleIOSecondaryMDMIP(returnVal.getId(), ip);
            domainIP.setScaleIOData(returnVal);
            secondaryMdmActorIps.add(domainIP);
        }
        returnVal.setSecondaryMDMIPList(secondaryMdmActorIps);
    }

    private void linkPrimaryMdmIPList(final ScaleIOData returnVal, final ScaleIOSystemDataRestRep scaleIOSystemDataRestRep)
    {
        //Link primaryMdmActorIpList
        List<ScaleIOIP> primaryMdmActorIps = new ArrayList<>();
        for (String ip : scaleIOSystemDataRestRep.getPrimaryMdmActorIpList())
        {
            ScaleIOIP domainIP = new ScaleIOPrimaryMDMIP(returnVal.getId(), ip);
            domainIP.setScaleIOData(returnVal);
            primaryMdmActorIps.add(domainIP);
        }
        returnVal.setPrimaryMDMIPList(primaryMdmActorIps);
    }

    private void linkTieBreakerMdmIPList(final ScaleIOData returnVal, final ScaleIOSystemDataRestRep scaleIOSystemDataRestRep)
    {
        //Link tiebreakerMdmIPList
        List<ScaleIOIP> tiebreakerDomainIps = new ArrayList<>();
        for (String ip : scaleIOSystemDataRestRep.getTiebreakerMdmIpList())
        {
            ScaleIOIP domainIP = new ScaleIOTiebreakerScaleIOIP(returnVal.getId(), ip);
            domainIP.setScaleIOData(returnVal);
            tiebreakerDomainIps.add(domainIP);
        }
        returnVal.setTiebreakerScaleIOList(tiebreakerDomainIps);
    }
}