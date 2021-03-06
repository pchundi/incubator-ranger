/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.plugin.audit;

import java.util.*;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ranger.audit.model.AuthzAuditEvent;
import org.apache.ranger.audit.provider.AuditProviderFactory;
import org.apache.ranger.audit.provider.MiscUtil;
import org.apache.ranger.authorization.hadoop.config.RangerConfiguration;
import org.apache.ranger.authorization.hadoop.constants.RangerHadoopConstants;
import org.apache.ranger.plugin.model.RangerTag;
import org.apache.ranger.plugin.policyengine.*;
import org.apache.ranger.plugin.util.RangerAccessRequestUtil;


public class RangerDefaultAuditHandler implements RangerAccessResultProcessor {
	protected static final String RangerModuleName =  RangerConfiguration.getInstance().get(RangerHadoopConstants.AUDITLOG_RANGER_MODULE_ACL_NAME_PROP , RangerHadoopConstants.DEFAULT_RANGER_MODULE_ACL_NAME) ;

	private static final Log LOG = LogFactory.getLog(RangerDefaultAuditHandler.class);
	static long sequenceNumber = 0;

	public RangerDefaultAuditHandler() {
	}

	@Override
	public void processResult(RangerAccessResult result) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerDefaultAuditHandler.processResult(" + result + ")");
		}

		AuthzAuditEvent event = getAuthzEvents(result);

		logAuthzAudit(event);

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerDefaultAuditHandler.processResult(" + result + ")");
		}
	}

	@Override
	public void processResults(Collection<RangerAccessResult> results) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerDefaultAuditHandler.processResults(" + results + ")");
		}

		Collection<AuthzAuditEvent> events = getAuthzEvents(results);

		if (events != null) {
			logAuthzAudits(events);
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerDefaultAuditHandler.processResults(" + results + ")");
		}
	}


	public AuthzAuditEvent getAuthzEvents(RangerAccessResult result) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerDefaultAuditHandler.getAuthzEvents(" + result + ")");
		}

		AuthzAuditEvent ret = null;

		RangerAccessRequest request = result != null ? result.getAccessRequest() : null;

		if(request != null && result != null && result.getIsAudited()) {
			//RangerServiceDef     serviceDef   = result.getServiceDef();
			RangerAccessResource resource     = request.getResource();
			String               resourceType = resource == null ? null : resource.getLeafName();
			String               resourcePath = resource == null ? null : resource.getAsString();

			ret = createAuthzAuditEvent();

			ret.setRepositoryName(result.getServiceName());
			ret.setRepositoryType(result.getServiceType());
			ret.setResourceType(resourceType);
			ret.setResourcePath(resourcePath);
			ret.setRequestData(request.getRequestData());
			ret.setEventTime(request.getAccessTime());
			ret.setUser(request.getUser());
			ret.setAction(request.getAccessType());
			ret.setAccessResult((short) (result.getIsAllowed() ? 1 : 0));
			ret.setPolicyId(result.getPolicyId());
			ret.setAccessType(request.getAction());
			ret.setClientIP(request.getClientIPAddress());
			ret.setClientType(request.getClientType());
			ret.setSessionId(request.getSessionId());
			ret.setAclEnforcer(RangerModuleName);
			Set<String> tags = getTags(request);
			if (tags != null) {
				ret.setTags(tags);
			}
			ret.setAdditionalInfo(getAdditionalInfo(request));

			populateDefaults(ret);
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerDefaultAuditHandler.getAuthzEvents(" + result + "): " + ret);
		}

		return ret;
	}

	public Collection<AuthzAuditEvent> getAuthzEvents(Collection<RangerAccessResult> results) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerDefaultAuditHandler.getAuthzEvents(" + results + ")");
		}

		List<AuthzAuditEvent> ret = null;

		if(results != null) {
			// TODO: optimize the number of audit logs created
			for(RangerAccessResult result : results) {
				AuthzAuditEvent event = getAuthzEvents(result);

				if(event == null) {
					continue;
				}

				if(ret == null) {
					ret = new ArrayList<AuthzAuditEvent>();
				}

				ret.add(event);
			}
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerDefaultAuditHandler.getAuthzEvents(" + results + "): " + ret);
		}

		return ret;
	}

	public void logAuthzAudit(AuthzAuditEvent auditEvent) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerDefaultAuditHandler.logAuthzAudit(" + auditEvent + ")");
		}

		if(auditEvent != null) {
			populateDefaults(auditEvent);
			AuditProviderFactory.getAuditProvider().log(auditEvent);
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerDefaultAuditHandler.logAuthzAudit(" + auditEvent + ")");
		}
	}

	private void populateDefaults(AuthzAuditEvent auditEvent) {
		if( auditEvent.getAclEnforcer() == null || auditEvent.getAclEnforcer().isEmpty()) {
			auditEvent.setAclEnforcer("ranger-acl"); // TODO: review
		}

		if (auditEvent.getAgentHostname() == null || auditEvent.getAgentHostname().isEmpty()) {
			auditEvent.setAgentHostname(MiscUtil.getHostname());
		}

		if (auditEvent.getLogType() == null || auditEvent.getLogType().isEmpty()) {
			auditEvent.setLogType("RangerAudit");
		}

		if (auditEvent.getEventId() == null || auditEvent.getEventId().isEmpty()) {
			auditEvent.setEventId(MiscUtil.generateUniqueId());
		}

		auditEvent.setSeqNum(sequenceNumber++);
	}

	public void logAuthzAudits(Collection<AuthzAuditEvent> auditEvents) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerDefaultAuditHandler.logAuthzAudits(" + auditEvents + ")");
		}

		if(auditEvents != null) {
			for(AuthzAuditEvent auditEvent : auditEvents) {
				logAuthzAudit(auditEvent);
			}
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerDefaultAuditHandler.logAuthzAudits(" + auditEvents + ")");
		}
	}

	public AuthzAuditEvent createAuthzAuditEvent() {
		return new AuthzAuditEvent();
	}

	protected final Set<String> getTags(RangerAccessRequest request) {
		Set<String>     ret  = null;
		List<RangerTag> tags = RangerAccessRequestUtil.getRequestTagsFromContext(request.getContext());

		if (CollectionUtils.isNotEmpty(tags)) {
			ret = new HashSet<String>();

			for (RangerTag tag : tags) {
				ret.add(tag.getType());
			}
		}

		return ret;
	}

	public 	String getAdditionalInfo(RangerAccessRequest request) {
		if (StringUtils.isBlank(request.getRemoteIPAddress()) && CollectionUtils.isEmpty(request.getForwardedAddresses())) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("{\"remote-ip-address\":").append(request.getRemoteIPAddress())
				.append(", \"forwarded-ip-addresses\":[").append(StringUtils.join(request.getForwardedAddresses(), ", ")).append("]");

		return sb.toString();
	}
}
