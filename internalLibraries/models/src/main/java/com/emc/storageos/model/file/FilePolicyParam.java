package com.emc.storageos.model.file;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "file_system_policy")
public class FilePolicyParam {

    private String policyName;
    private String policyPattern;
    private String policyDuration;
    private FilePolicyScheduleParam policySchedule;

    @XmlElement(name = "policy_name")
    public String getPolicyName() {
        return policyName;
    }

    public void setPolicyName(String policyName) {
        this.policyName = policyName;
    }

    @XmlElement(name = "policy_schedule")
    public FilePolicyScheduleParam getPolicySchedule() {
        return policySchedule;
    }

    public void setPolicySchedule(FilePolicyScheduleParam policySchedule) {
        this.policySchedule = policySchedule;
    }

    @XmlElement(name = "policy_pattern")
    public String getPolicyPattern() {
        return policyPattern;
    }

    public void setPolicyPattern(String policyPattern) {
        this.policyPattern = policyPattern;
    }

    @XmlElement(name = "policy_duration")
    public String getPolicyDuration() {
        return policyDuration;
    }

    public void setPolicyDuration(String policyDuration) {
        this.policyDuration = policyDuration;
    }
}
