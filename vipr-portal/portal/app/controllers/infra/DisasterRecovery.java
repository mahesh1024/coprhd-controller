/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */

package controllers.infra;

import com.emc.storageos.coordinator.client.model.SiteState;
import com.emc.storageos.model.dr.SiteActive;
import com.emc.storageos.model.dr.SiteAddParam;
import com.emc.storageos.model.dr.SiteDetailRestRep;
import com.emc.storageos.model.dr.SiteErrorResponse;
import com.emc.storageos.model.dr.SiteIdListParam;
import com.emc.storageos.model.dr.SiteRestRep;
import com.emc.storageos.model.dr.SiteUpdateParam;
import com.emc.vipr.client.exceptions.ServiceErrorException;
import com.emc.vipr.model.sys.ClusterInfo;
import com.google.common.collect.Lists;
import controllers.Common;
import controllers.deadbolt.Restrict;
import controllers.deadbolt.Restrictions;
import controllers.util.FlashException;
import controllers.util.ViprResourceController;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import models.datatable.DisasterRecoveryDataTable;
import models.datatable.DisasterRecoveryDataTable.StandByInfo;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;
import play.data.binding.As;
import play.data.validation.MaxSize;
import play.data.validation.Required;
import play.data.validation.Validation;
import play.mvc.With;
import util.DisasterRecoveryUtils;
import util.MessagesUtils;
import util.datatable.DataTablesSupport;
import util.validation.HostNameOrIpAddress;

@With(Common.class)
@Restrictions({ @Restrict("SECURITY_ADMIN"), @Restrict("RESTRICTED_SECURITY_ADMIN"), @Restrict("SYSTEM_MONITOR"),
        @Restrict("SYSTEM_ADMIN"), @Restrict("RESTRICTED_SYSTEM_ADMIN")})
public class DisasterRecovery extends ViprResourceController {
    protected static final String SAVED_SUCCESS = "disasterRecovery.save.success";
    protected static final String PAUSED_SUCCESS = "disasterRecovery.pause.success";
    protected static final String PAUSED_ERROR = "disasterRecovery.pause.error";
    protected static final String SWITCHOVER_SUCCESS = "disasterRecovery.switchover.success";
    protected static final String SWITCHOVER_ERROR = "disasterRecovery.switchover.error";
    protected static final String RESUMED_SUCCESS = "disasterRecovery.resume.success";
    protected static final String RETRY_SUCCESS = "disasterRecovery.retry.success";
    protected static final String SAVED_ERROR = "disasterRecovery.save.error";
    protected static final String DELETED_SUCCESS = "disasterRecovery.delete.success";
    protected static final String DELETED_ERROR = "disasterRecovery.delete.error";
    protected static final String UNKNOWN = "disasterRecovery.unknown";
    protected static final String UPDATE_SUCCESS = "disasterRecovery.update.success";
    protected static final String ADD_WARNING = "disasterRecovery.add.unstable.warning";
    private static final List<SiteState> activeStates =
            Arrays.asList(SiteState.ACTIVE,SiteState.ACTIVE_FAILING_OVER, SiteState.ACTIVE_SWITCHING_OVER);

    private static void backToReferrer() {
        String referrer = Common.getReferrer();
        if (StringUtils.isNotBlank(referrer)) {
            redirect(referrer);
        }
        else {
            list();
        }
    }

    private static void list() {
        list(false);
    }

    public static void list(boolean showPauseButton) {
        DisasterRecoveryDataTable dataTable = createDisasterRecoveryDataTable();
        String localSiteUuid = DisasterRecoveryUtils.getLocalUuid();
        render(dataTable, showPauseButton, localSiteUuid);
    }

    @FlashException("list")
    @Restrictions({ @Restrict("SECURITY_ADMIN"), @Restrict("RESTRICTED_SECURITY_ADMIN"), @Restrict("SYSTEM_ADMIN"),
            @Restrict("RESTRICTED_SYSTEM_ADMIN") })
    public static void pause(@As(",") String[] ids) {
        List<String> uuids = Arrays.asList(ids);
        for (String uuid : uuids) {
            if (!DisasterRecoveryUtils.hasStandbySite(uuid)) {
                flash.error(MessagesUtils.get(UNKNOWN, uuid));
                list(true);
            }
        }

        SiteIdListParam param = new SiteIdListParam();
        param.getIds().addAll(uuids);
        try {
            DisasterRecoveryUtils.pauseStandby(param);
        } catch (ServiceErrorException ex) {
            flash.error(ex.getDetailedMessage());
            list(true);
        } catch (Exception ex) {
            flash.error(ex.getMessage());
            list(true);
        }

        flash.success(MessagesUtils.get(PAUSED_SUCCESS));
        list(true);
    }

    @FlashException("list")
    @Restrictions({ @Restrict("SECURITY_ADMIN"), @Restrict("RESTRICTED_SECURITY_ADMIN"), @Restrict("SYSTEM_ADMIN"),
            @Restrict("RESTRICTED_SYSTEM_ADMIN") })
    public static void resume(String id) {
        SiteRestRep result = DisasterRecoveryUtils.getSite(id);
        if (result != null) {
            SiteRestRep siteresume = DisasterRecoveryUtils.resumeStandby(id);
            flash.success(MessagesUtils.get(RESUMED_SUCCESS, siteresume.getName()));
        }

        list();
    }

    @FlashException("list")
    @Restrictions({ @Restrict("SECURITY_ADMIN"), @Restrict("RESTRICTED_SECURITY_ADMIN"), @Restrict("SYSTEM_ADMIN"),
            @Restrict("RESTRICTED_SYSTEM_ADMIN") })
    public static void retry(String id) {
        SiteRestRep result = DisasterRecoveryUtils.getSite(id);
        if (result != null) {
            SiteRestRep siteretry = DisasterRecoveryUtils.retryStandby(id);
            if (siteretry.getState().equals(SiteState.STANDBY_FAILING_OVER.name())){
                String standby_name = siteretry.getName();
                String standby_vip = siteretry.getVipEndpoint();
                String active_name = null;
                for (SiteRestRep site: DisasterRecoveryUtils.getStandbySites()){
                    if (site.getState().equals(SiteState.ACTIVE_FAILING_OVER.name()) ||
                            site.getState().equals(SiteState.ACTIVE_DEGRADED.name())){
                        active_name = site.getName();
                        break;
                    }
                }
                Boolean isSwitchover = false;
                String site_uuid = id;
                maintenance(active_name, standby_name, standby_vip, site_uuid, isSwitchover);
            }
            else {
                flash.success(MessagesUtils.get(RETRY_SUCCESS, siteretry.getName()));
                list();
            }
        }
        else {
            flash.error(MessagesUtils.get(UNKNOWN, id));
            list();
        }
    }

    public static void test(String id) {

    }

    @FlashException("list")
    @Restrictions({ @Restrict("SECURITY_ADMIN"), @Restrict("RESTRICTED_SECURITY_ADMIN") })
    public static void switchover(String id) {
        String standby_name = null;
        String standby_vip = null;
        String active_name = null;
        Boolean isSwitchover = false;

        // Get active site details
        SiteRestRep activesite = DisasterRecoveryUtils.getActiveSite();
        active_name = activesite == null ? "N/A" : activesite.getName();

        SiteRestRep result = DisasterRecoveryUtils.getSite(id);
        if (result != null) {
            // Check Switchover or Failover
            SiteActive currentSite = DisasterRecoveryUtils.checkActiveSite();
            if (currentSite.getIsActive() == true) {
                DisasterRecoveryUtils.doSwitchover(id);
                isSwitchover = true;
            }
            else {
                DisasterRecoveryUtils.doFailover(id);
                isSwitchover = false;
            }
            standby_name = result.getName();
            standby_vip = result.getVipEndpoint();
        }
        String site_uuid = id;
        maintenance(active_name, standby_name, standby_vip, site_uuid, isSwitchover);
    }

    @FlashException("list")
    @Restrictions({ @Restrict("SECURITY_ADMIN"), @Restrict("RESTRICTED_SECURITY_ADMIN") })
    public static void maintenance(String active_name, String standby_name, String standby_vip, String site_uuid, Boolean isSwitchover) {
        render(active_name, standby_name, standby_vip, site_uuid, isSwitchover);
    }

    private static DisasterRecoveryDataTable createDisasterRecoveryDataTable() {
        DisasterRecoveryDataTable dataTable = new DisasterRecoveryDataTable();
        return dataTable;
    }

    public static void listJson() {
        List<DisasterRecoveryDataTable.StandByInfo> disasterRecoveries = Lists.newArrayList();
        for (SiteRestRep siteConfig : DisasterRecoveryUtils.getSites()) {
            disasterRecoveries.add(new StandByInfo(siteConfig));
        }
        renderJSON(DataTablesSupport.createJSON(disasterRecoveries, params));
    }

    @Restrictions({ @Restrict("SECURITY_ADMIN"), @Restrict("RESTRICTED_SECURITY_ADMIN") })
    public static void create() {
        for (SiteRestRep site : DisasterRecoveryUtils.getSites()) {
            if (SiteState.STANDBY_PAUSED.toString().equals(site.getState())) {
                continue;
            }
            SiteDetailRestRep detail = DisasterRecoveryUtils.getSiteDetails(site.getUuid());
            if (!ClusterInfo.ClusterState.STABLE.toString().equals(detail.getClusterState())) {
                flash.error(MessagesUtils.get(ADD_WARNING, site.getName()));
                list();
            }
        }

        DisasterRecoveryForm site = new DisasterRecoveryForm();
        edit(site);
    }

    @Restrictions({ @Restrict("SECURITY_ADMIN"), @Restrict("RESTRICTED_SECURITY_ADMIN") })
    public static void edit(String id) {
        SiteRestRep siteRest = DisasterRecoveryUtils.getSite(id);
        if (siteRest != null) {
            DisasterRecoveryForm disasterRecovery = new DisasterRecoveryForm(siteRest);
            edit(disasterRecovery);
        }
        else {
            flash.error(MessagesUtils.get(UNKNOWN, id));
            list();
        }
    }

    private static void edit(DisasterRecoveryForm disasterRecovery) {
        render("@edit", disasterRecovery);
    }

    @FlashException(keep = true, referrer = { "create", "edit" })
    @Restrictions({ @Restrict("SECURITY_ADMIN"), @Restrict("RESTRICTED_SECURITY_ADMIN") })
    public static void save(DisasterRecoveryForm disasterRecovery) {
        if (disasterRecovery != null) {
            disasterRecovery.validate("disasterRecovery");
            if (Validation.hasErrors()) {
                Common.handleError();
            }
            if (disasterRecovery.isNew()) {
                SiteAddParam standbySite = new SiteAddParam();
                standbySite.setName(disasterRecovery.name);
                standbySite.setVip(disasterRecovery.VirtualIP);
                standbySite.setUsername(disasterRecovery.userName);
                standbySite.setPassword(disasterRecovery.userPassword);
                standbySite.setDescription(disasterRecovery.description);

                SiteRestRep result = DisasterRecoveryUtils.addStandby(standbySite);
                flash.success(MessagesUtils.get(SAVED_SUCCESS, result.getName()));
                list();
            }
            else {
                SiteUpdateParam siteUpdateParam = new SiteUpdateParam();
                siteUpdateParam.setName(disasterRecovery.name);
                siteUpdateParam.setDescription(disasterRecovery.description);
                DisasterRecoveryUtils.updateSite(disasterRecovery.id, siteUpdateParam);
                flash.success(MessagesUtils.get(UPDATE_SUCCESS, disasterRecovery.name));
                list();
            }
        }
    }

    @FlashException("list")
    @Restrictions({ @Restrict("SECURITY_ADMIN"), @Restrict("RESTRICTED_SECURITY_ADMIN") })
    public static void delete(@As(",") String[] ids) {
        List<String> uuids = Arrays.asList(ids);
        for (String uuid : uuids) {
            if (!DisasterRecoveryUtils.hasStandbySite(uuid)) {
                flash.error(MessagesUtils.get(UNKNOWN, uuid));
                list();
            }

        }

        SiteIdListParam param = new SiteIdListParam();
        param.getIds().addAll(uuids);
        DisasterRecoveryUtils.deleteStandby(param);
        flash.success(MessagesUtils.get(DELETED_SUCCESS));
        list();
    }

    public static void itemsJson(@As(",") String[] ids) {
        List<String> uuids = Arrays.asList(ids);
        itemsJson(uuids);
    }

    public static boolean isActiveSite() {
        return DisasterRecoveryUtils.isActiveSite();
    }

    public static boolean isLocalsiteRemoved() {
        return DisasterRecoveryUtils.isLocalSiteRemoved();
    }

    public static boolean isMultiDrSite() {
        return DisasterRecoveryUtils.isMultiDrSite();
    }

    public static boolean isRetrySite(String uuid) {
        SiteErrorResponse error = DisasterRecoveryUtils.getSiteError(uuid);
        if(!error.getOperation().equals(SiteState.STANDBY_PAUSING.name())
                && !error.getOperation().equals(SiteState.STANDBY_RESUMING.name())
                && !error.getOperation().equals(SiteState.STANDBY_FAILING_OVER.name())){
            return false;
        }
        return true;
    }

    public static String getLocalSiteName() {
        return DisasterRecoveryUtils.getLocalSiteName();
    }

    public static void checkFailoverProgress(String uuid) {
        SiteRestRep siteRest = DisasterRecoveryUtils.getSite(uuid);
        renderJSON(siteRest);
    }

    private static boolean isActiveSiteState(SiteState state) {
        return activeStates.contains(state);
    }
    
    public static String getLocalSiteState() {
        SiteRestRep site = DisasterRecoveryUtils.getLocalSite();
        return site != null ? site.getState() : "";
    }

    public static void errorDetails(String id) {
        Boolean isError = false;
        String uuid = id;

        // site id doesn't exist
        if (!DisasterRecoveryUtils.hasStandbySite(id)) {
            SiteDetailRestRep disasterSiteTime = new SiteDetailRestRep();
            uuid = "Unknown Standby site id: " + id;
            render(isError, uuid, disasterSiteTime);
        }

        SiteRestRep siteRest = DisasterRecoveryUtils.getSite(id);

        // site is in STANDBY_ERROR state
        if (siteRest.getState().equals(String.valueOf(SiteState.STANDBY_ERROR))) {
            SiteErrorResponse disasterSiteError = DisasterRecoveryUtils.getSiteError(id);
            isError = true;

            if (disasterSiteError.getCreationTime() != null) {
                DateTime errorCreationTime = new DateTime(disasterSiteError.getCreationTime().getTime());
                renderArgs.put("errorCreationTime", errorCreationTime);
            }

            DateTime siteCreationTime = new DateTime(siteRest.getCreateTime());
            renderArgs.put("siteCreationTime", siteCreationTime);

            if (disasterSiteError.getOperation() != null) {
                String operation = disasterSiteError.getOperation();
                renderArgs.put("operation", operation);
            }

            render(isError, uuid, disasterSiteError);
        }

        SiteDetailRestRep disasterSiteDetails = DisasterRecoveryUtils.getSiteDetails(id);
        Boolean isActive = isActiveSiteState(Enum.valueOf(SiteState.class, siteRest.getState()));
        renderArgs.put("isActive", isActive);

        if (disasterSiteDetails.getLastSyncTime() != null) {
            DateTime lastSyncTime = new DateTime(disasterSiteDetails.getLastSyncTime().getTime());
            renderArgs.put("lastSyncTime", lastSyncTime);
        }

        if (disasterSiteDetails.getCreationTime() != null) {
            DateTime creationTime = new DateTime(disasterSiteDetails.getCreationTime().getTime());
            renderArgs.put("creationTime", creationTime);
        }
        render(isError, uuid, disasterSiteDetails);
    }

    public static boolean hasPausedSite() {
        return DisasterRecoveryUtils.hasPausedSite();
    }
    
    public static boolean hasActiveDegradedSite() {
        return DisasterRecoveryUtils.hasActiveDegradedSite();
    }
    
    private static void itemsJson(List<String> uuids) {
        List<SiteRestRep> standbySites = new ArrayList<SiteRestRep>();
        for (String uuid : uuids) {
            SiteRestRep standbySite = DisasterRecoveryUtils.getSite(uuid);
            if (standbySite != null) {
                standbySites.add(standbySite);
            }
        }
        performItemsJson(standbySites, new JsonItemOperation());
    }

    protected static class JsonItemOperation implements ResourceValueOperation<StandByInfo, SiteRestRep> {
        @Override
        public StandByInfo performOperation(SiteRestRep provider) throws Exception {
            return new StandByInfo(provider);
        }
    }
    
    // Suppressing Sonar violation of Password Hardcoded. Password is not hardcoded here.
    @SuppressWarnings("squid:S2068")
    public static class DisasterRecoveryForm {
        public String id;

        @MaxSize(2048)
        @Required
        public String name;

        @Required
        @HostNameOrIpAddress
        public String VirtualIP;

        @MaxSize(2048)
        public String userName;

        @MaxSize(2048)
        public String userPassword;

        @MaxSize(2048)
        public String confirmPassword;

        @MaxSize(2048)
        public String description;

        public DisasterRecoveryForm() {
            this.userPassword = "";
            this.confirmPassword = "";
        }

        public DisasterRecoveryForm(SiteAddParam siteaddParam) {
            this.id = siteaddParam.getId();
            this.name = siteaddParam.getName();
            this.userName = siteaddParam.getUsername();
            this.VirtualIP = siteaddParam.getVip();
            this.description = siteaddParam.getDescription();
        }

        public DisasterRecoveryForm(SiteRestRep siteeditParam) {
            this.id = siteeditParam.getUuid();
            this.name = siteeditParam.getName();
            this.description = siteeditParam.getDescription();
            this.VirtualIP = siteeditParam.getVipEndpoint();
        }

        public boolean isNew() {
            return StringUtils.isBlank(id);
        }

        public void validate(String fieldName) {
            if (isNew()) {
                Validation.valid(fieldName, this);
                Validation.required(fieldName + ".name", this.name);
                Validation.required(fieldName + ".VirtualIP", this.VirtualIP);
                Validation.required(fieldName + ".userName", this.userName);
                Validation.required(fieldName + ".userPassword", this.userPassword);
                Validation.required(fieldName + ".confirmPassword", this.confirmPassword);

                if (!isMatchingPasswords(userPassword, confirmPassword)) {
                    Validation.addError(fieldName + ".confirmPassword",
                            MessagesUtils.get("storageArray.confirmPassword.not.match"));
                }
            }
        }

        private boolean isMatchingPasswords(String password, String confirm) {
            return StringUtils.equals(StringUtils.trimToEmpty(password), StringUtils.trimToEmpty(confirm));
        }

    }
}
