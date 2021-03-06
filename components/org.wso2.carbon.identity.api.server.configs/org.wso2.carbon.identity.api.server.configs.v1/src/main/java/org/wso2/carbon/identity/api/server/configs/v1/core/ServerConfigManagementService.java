/*
 * Copyright (c) 2020 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
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

package org.wso2.carbon.identity.api.server.configs.v1.core;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.api.server.common.ContextLoader;
import org.wso2.carbon.identity.api.server.common.error.APIError;
import org.wso2.carbon.identity.api.server.common.error.ErrorResponse;
import org.wso2.carbon.identity.api.server.configs.common.ConfigsServiceHolder;
import org.wso2.carbon.identity.api.server.configs.common.Constants;
import org.wso2.carbon.identity.api.server.configs.v1.model.Authenticator;
import org.wso2.carbon.identity.api.server.configs.v1.model.AuthenticatorListItem;
import org.wso2.carbon.identity.api.server.configs.v1.model.AuthenticatorProperty;
import org.wso2.carbon.identity.api.server.configs.v1.model.InboundConfig;
import org.wso2.carbon.identity.api.server.configs.v1.model.Patch;
import org.wso2.carbon.identity.api.server.configs.v1.model.ProvisioningConfig;
import org.wso2.carbon.identity.api.server.configs.v1.model.ScimConfig;
import org.wso2.carbon.identity.api.server.configs.v1.model.ServerConfig;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementClientException;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementServerException;
import org.wso2.carbon.identity.application.common.model.FederatedAuthenticatorConfig;
import org.wso2.carbon.identity.application.common.model.IdentityProvider;
import org.wso2.carbon.identity.application.common.model.IdentityProviderProperty;
import org.wso2.carbon.identity.application.common.model.InboundProvisioningConfig;
import org.wso2.carbon.identity.application.common.model.LocalAuthenticatorConfig;
import org.wso2.carbon.identity.application.common.model.Property;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationConstants;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationManagementUtil;
import org.wso2.carbon.identity.application.mgt.ApplicationConstants;
import org.wso2.carbon.idp.mgt.IdentityProviderManagementClientException;
import org.wso2.carbon.idp.mgt.IdentityProviderManagementException;
import org.wso2.carbon.idp.mgt.IdentityProviderManagementServerException;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.ws.rs.core.Response;

import static org.wso2.carbon.identity.api.server.common.Constants.V1_API_PATH_COMPONENT;
import static org.wso2.carbon.identity.api.server.common.Util.base64URLDecode;
import static org.wso2.carbon.identity.api.server.common.Util.base64URLEncode;
import static org.wso2.carbon.identity.api.server.configs.common.Constants.CONFIGS_AUTHENTICATOR_PATH_COMPONENT;
import static org.wso2.carbon.identity.api.server.configs.common.Constants.PATH_SEPERATOR;

/**
 * Call internal osgi services to perform server configuration management.
 */
public class ServerConfigManagementService {

    private static final Log log = LogFactory.getLog(ServerConfigManagementService.class);

    /**
     * Get list of local authenticators supported by the server.
     *
     * @return List of authenticator basic information.
     */
    public List<AuthenticatorListItem> getAuthenticators() {

        try {
            LocalAuthenticatorConfig[] authenticatorConfigs = ConfigsServiceHolder.getInstance()
                    .getApplicationManagementService()
                    .getAllLocalAuthenticators(ContextLoader.getTenantDomainFromContext());
            return buildAuthenticatorListResponse(authenticatorConfigs);
        } catch (IdentityApplicationManagementException e) {
            throw handleApplicationMgtException(e, Constants.ErrorMessage.ERROR_CODE_ERROR_LISTING_AUTHENTICATORS,
                    null);
        }
    }

    /**
     * Get an authenticator identified by its resource ID.
     *
     * @param authenticatorId Authenticator resource ID.
     * @return Authenticator.
     */
    public Authenticator getAuthenticator(String authenticatorId) {

        try {
            LocalAuthenticatorConfig authenticatorConfig = getAuthenticatorById(
                    ConfigsServiceHolder.getInstance().getApplicationManagementService().getAllLocalAuthenticators(
                            ContextLoader.getTenantDomainFromContext()), authenticatorId);
            if (authenticatorConfig == null) {
                throw handleException(Response.Status.NOT_FOUND,
                        Constants.ErrorMessage.ERROR_CODE_AUTHENTICATOR_NOT_FOUND, authenticatorId);
            }
            return buildAuthenticatorResponse(authenticatorConfig);
        } catch (IdentityApplicationManagementException e) {
            throw handleApplicationMgtException(e, Constants.ErrorMessage.ERROR_CODE_ERROR_RETRIEVING_AUTHENTICATOR,
                    authenticatorId);
        }
    }

    /**
     * Get Server Configs.
     *
     * @return ServerConfig.
     */
    public ServerConfig getConfigs() {

        IdentityProvider residentIdP = getResidentIdP();

        String idleSessionTimeout = null;
        IdentityProviderProperty idleSessionProp = IdentityApplicationManagementUtil.getProperty(
                residentIdP.getIdpProperties(), IdentityApplicationConstants.SESSION_IDLE_TIME_OUT);
        if (idleSessionProp != null) {
            idleSessionTimeout = idleSessionProp.getValue();
        }

        String rememberMePeriod = null;
        IdentityProviderProperty rememberMeProp = IdentityApplicationManagementUtil.getProperty(
                residentIdP.getIdpProperties(), IdentityApplicationConstants.REMEMBER_ME_TIME_OUT);
        if (rememberMeProp != null) {
            rememberMePeriod = rememberMeProp.getValue();
        }

        String homeRealmIdStr = residentIdP.getHomeRealmId();
        List<String> homeRealmIdentifiers = null;
        if (StringUtils.isNotBlank(homeRealmIdStr)) {
            homeRealmIdentifiers = Arrays.stream(homeRealmIdStr.split(",")).collect(Collectors.toList());
        }
        ServerConfig serverConfig = new ServerConfig();
        serverConfig.setIdleSessionTimeoutPeriod(idleSessionTimeout);
        serverConfig.setRememberMePeriod(rememberMePeriod);
        serverConfig.setHomeRealmIdentifiers(homeRealmIdentifiers);
        serverConfig.setProvisioning(buildProvisioningConfig());
        serverConfig.setAuthenticators(getAuthenticators());
        return serverConfig;
    }

    /**
     * Patch Server Configs. Patch 'REPLACE', 'ADD', 'REMOVE' operations have been supported for primary attributes in
     * ServerConfig model.
     *
     * @param patchRequest Patch request in Json Patch notation See
     *                     <a href="https://tools.ietf.org/html/rfc6902">https://tools.ietf
     *                     .org/html/rfc6902</a>.
     */
    public void patchConfigs(List<Patch> patchRequest) {

        try {
            IdentityProvider residentIdP =
                    ConfigsServiceHolder.getInstance().getIdentityProviderManager().getResidentIdP(ContextLoader
                            .getTenantDomainFromContext());
            // Resident Identity Provider can be null only due to an internal server error.
            if (residentIdP == null) {
                throw handleException(Response.Status.INTERNAL_SERVER_ERROR, Constants.ErrorMessage
                        .ERROR_CODE_ERROR_UPDATING_CONFIGS, null);
            }
            IdentityProvider idpToUpdate = createIdPClone(residentIdP);
            processPatchRequest(patchRequest, idpToUpdate);
            // To avoid updating non-existing authenticators in DB layer.
            idpToUpdate.setFederatedAuthenticatorConfigs(new FederatedAuthenticatorConfig[0]);
            ConfigsServiceHolder.getInstance().getIdentityProviderManager()
                    .updateResidentIdP(idpToUpdate, ContextLoader.getTenantDomainFromContext());

        } catch (IdentityProviderManagementException e) {
            throw handleIdPException(e, Constants.ErrorMessage.ERROR_CODE_ERROR_UPDATING_CONFIGS, null);
        }
    }

    /**
     * Get SCIM Inbound Provisioning Configurations.
     *
     * @return ScimConfig.
     */
    public ScimConfig getInboundScimConfig() {

        ServiceProvider application = getResidentApplication();
        ScimConfig scimConfig = new ScimConfig();
        scimConfig.setEnableProxyMode(application.getInboundProvisioningConfig().isDumbMode());
        scimConfig.setProvisioningUserstore(application.getInboundProvisioningConfig().getProvisioningUserStore());
        return scimConfig;
    }

    /**
     * Update SCIM Inbound Provisioning Configurations.
     *
     * @param scimConfig ScimConfig.
     */
    public void updateInboundScimConfigs(ScimConfig scimConfig) {

        ServiceProvider application = getResidentApplication();

        if (scimConfig != null && application != null) {
            InboundProvisioningConfig inboundProvisioningConfig = new InboundProvisioningConfig();
            inboundProvisioningConfig.setDumbMode(scimConfig.getEnableProxyMode());
            if (!scimConfig.getEnableProxyMode()) {
                inboundProvisioningConfig.setProvisioningEnabled(true);
                inboundProvisioningConfig.setProvisioningUserStore(scimConfig.getProvisioningUserstore());
            }
            ServiceProvider applicationClone = createApplicationClone(application);
            applicationClone.setInboundProvisioningConfig(inboundProvisioningConfig);

            try {
                ConfigsServiceHolder.getInstance().getApplicationManagementService().updateApplicationByResourceId
                        (applicationClone.getApplicationResourceId(), applicationClone, ContextLoader
                                .getTenantDomainFromContext(), ContextLoader.getUsernameFromContext());
            } catch (IdentityApplicationManagementException e) {
                throw handleApplicationMgtException(e, Constants.ErrorMessage.ERROR_CODE_ERROR_RETRIEVING_CONFIGS,
                        null);
            }
        }
    }

    private List<AuthenticatorListItem> buildAuthenticatorListResponse(
            LocalAuthenticatorConfig[] authenticatorConfigs) {

        List<AuthenticatorListItem> authenticatorListItems = new ArrayList<>();
        if (authenticatorConfigs != null) {
            for (LocalAuthenticatorConfig config : authenticatorConfigs) {
                AuthenticatorListItem authenticatorListItem = new AuthenticatorListItem();
                String authenticatorId = base64URLEncode(config.getName());
                authenticatorListItem.setId(authenticatorId);
                authenticatorListItem.setName(config.getName());
                authenticatorListItem.setDisplayName(config.getDisplayName());
                authenticatorListItem.setIsEnabled(config.isEnabled());
                authenticatorListItem.setSelf(ContextLoader.buildURIForBody(String.format(V1_API_PATH_COMPONENT +
                        CONFIGS_AUTHENTICATOR_PATH_COMPONENT + PATH_SEPERATOR + "%s", authenticatorId)).toString());
                authenticatorListItems.add(authenticatorListItem);
            }
        }
        return authenticatorListItems;
    }

    private LocalAuthenticatorConfig getAuthenticatorById(LocalAuthenticatorConfig[] authenticatorConfigs,
                                                          String authenticatorId) {

        String authenticatorName = base64URLDecode(authenticatorId);
        for (LocalAuthenticatorConfig config : authenticatorConfigs) {
            if (StringUtils.equals(authenticatorName, config.getName())) {
                return config;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Unable to find an authenticator with the name: " + authenticatorName);
        }
        return null;
    }

    private Authenticator buildAuthenticatorResponse(LocalAuthenticatorConfig config) {

        Authenticator authenticator = new Authenticator();
        authenticator.setId(base64URLEncode(config.getName()));
        authenticator.setName(config.getName());
        authenticator.setDisplayName(config.getDisplayName());
        authenticator.setIsEnabled(config.isEnabled());
        List<AuthenticatorProperty> authenticatorProperties =
                Arrays.stream(config.getProperties()).map(propertyToExternal)
                        .collect(Collectors.toList());
        authenticator.setProperties(authenticatorProperties);
        return authenticator;
    }

    private Function<Property, AuthenticatorProperty> propertyToExternal = property -> {

        AuthenticatorProperty authenticatorProperty = new AuthenticatorProperty();
        authenticatorProperty.setKey(property.getName());
        authenticatorProperty.setValue(property.getValue());
        return authenticatorProperty;
    };

    private ProvisioningConfig buildProvisioningConfig() {

        ProvisioningConfig provisioningConfig = new ProvisioningConfig();
        InboundConfig inboundConfig = new InboundConfig();
        inboundConfig.setScim(getInboundScimConfig());
        provisioningConfig.setInbound(inboundConfig);
        return provisioningConfig;
    }

    /**
     * Evaluate the list of patch operations and update the root level attributes of the ServerConfig accordingly.
     *
     * @param patchRequest List of patch operations.
     * @param idpToUpdate  Resident Identity Provider to be updated.
     */
    private void processPatchRequest(List<Patch> patchRequest, IdentityProvider idpToUpdate) {

        if (CollectionUtils.isEmpty(patchRequest)) {
            return;
        }
        for (Patch patch : patchRequest) {
            String path = patch.getPath();
            Patch.OperationEnum operation = patch.getOperation();
            String value = patch.getValue();
            // We support only 'REPLACE', 'ADD' and 'REMOVE' patch operations.
            if (operation == Patch.OperationEnum.REPLACE) {
                if (path.matches(Constants.HOME_REALM_PATH_REGEX) && path.split(Constants.PATH_SEPERATOR).length == 3) {

                    int index = Integer.parseInt(path.split(Constants.PATH_SEPERATOR)[2]);
                    String[] homeRealmArr = StringUtils.split(idpToUpdate.getHomeRealmId(), ",");
                    if (ArrayUtils.isNotEmpty(homeRealmArr) && (index >= 0)
                            && (index < homeRealmArr.length)) {
                        List<String> homeRealmIds = Arrays.asList(homeRealmArr);
                        homeRealmIds.set(index, value);
                        idpToUpdate.setHomeRealmId(StringUtils.join(homeRealmIds, ","));
                    } else {
                        throw handleException(Response.Status.BAD_REQUEST, Constants.ErrorMessage
                                .ERROR_CODE_INVALID_INPUT, "Invalid index in 'path' attribute");
                    }
                } else {
                    switch (path) {
                        case Constants.IDLE_SESSION_PATH:
                            updateIdPProperty(idpToUpdate, IdentityApplicationConstants.SESSION_IDLE_TIME_OUT, value);
                            break;
                        case Constants.REMEMBER_ME_PATH:
                            updateIdPProperty(idpToUpdate, IdentityApplicationConstants.REMEMBER_ME_TIME_OUT, value);
                            break;
                        default:
                            throw handleException(Response.Status.BAD_REQUEST, Constants.ErrorMessage
                                    .ERROR_CODE_INVALID_INPUT, "Unsupported value for 'path' attribute");
                    }
                }
            } else if (operation == Patch.OperationEnum.ADD && path.matches(Constants.HOME_REALM_PATH_REGEX) && path
                    .split(Constants.PATH_SEPERATOR).length == 3) {
                List<String> homeRealmIds;
                int index = Integer.parseInt(path.split(Constants.PATH_SEPERATOR)[2]);
                String[] homeRealmArr = StringUtils.split(idpToUpdate.getHomeRealmId(), ",");
                if (ArrayUtils.isNotEmpty(homeRealmArr) && (index >= 0) && index <= homeRealmArr.length) {
                    homeRealmIds = new ArrayList<>(Arrays.asList(homeRealmArr));
                    homeRealmIds.add(index, value);
                } else if (homeRealmArr == null && index == 0) {
                    homeRealmIds = new ArrayList<>();
                    homeRealmIds.add(value);
                } else {
                    throw handleException(Response.Status.BAD_REQUEST, Constants.ErrorMessage
                            .ERROR_CODE_INVALID_INPUT, "Invalid index in 'path' attribute");
                }
                idpToUpdate.setHomeRealmId(StringUtils.join(homeRealmIds, ","));
            } else if (operation == Patch.OperationEnum.REMOVE && path.matches(Constants.HOME_REALM_PATH_REGEX) &&
                    path.split(Constants.PATH_SEPERATOR).length == 3) {
                List<String> homeRealmIds;
                int index = Integer.parseInt(path.split(Constants.PATH_SEPERATOR)[2]);
                String[] homeRealmArr = StringUtils.split(idpToUpdate.getHomeRealmId(), ",");
                if (ArrayUtils.isNotEmpty(homeRealmArr) && (index >= 0) && index < homeRealmArr.length) {
                    homeRealmIds = new ArrayList<>(Arrays.asList(homeRealmArr));
                    homeRealmIds.remove(index);
                } else {
                    throw handleException(Response.Status.BAD_REQUEST, Constants.ErrorMessage
                            .ERROR_CODE_INVALID_INPUT, "Invalid index in 'path' attribute");
                }
                idpToUpdate.setHomeRealmId(StringUtils.join(homeRealmIds, ","));
            } else {
                // Throw an error if any other patch operations are sent in the request.
                throw handleException(Response.Status.BAD_REQUEST, Constants.ErrorMessage
                        .ERROR_CODE_INVALID_INPUT, "Unsupported patch operation");
            }
        }
    }

    private void updateIdPProperty(IdentityProvider identityProvider, String key, String value) {

        List<IdentityProviderProperty> idPProperties = new ArrayList<>(Arrays.asList(identityProvider
                .getIdpProperties()));
        if (StringUtils.isBlank(value) || !StringUtils.isNumeric(value) || Integer.parseInt(value) <= 0) {
            String message = "Value should be numeric and positive";
            throw handleException(Response.Status.BAD_REQUEST, Constants.ErrorMessage.ERROR_CODE_INVALID_INPUT,
                    message);
        }
        boolean isPropertyFound = false;
        if (CollectionUtils.isNotEmpty(idPProperties)) {
            for (IdentityProviderProperty property : idPProperties) {
                if (StringUtils.equals(key, property.getName())) {
                    isPropertyFound = true;
                    property.setValue(value);
                }
            }
        }
        if (!isPropertyFound) {
            IdentityProviderProperty property = new IdentityProviderProperty();
            property.setName(key);
            property.setDisplayName(key);
            property.setValue(value);
            idPProperties.add(property);
        }
        identityProvider.setIdpProperties(idPProperties.toArray(new IdentityProviderProperty[0]));
    }

    private IdentityProvider getResidentIdP() {

        IdentityProvider residentIdP;
        try {
            residentIdP = ConfigsServiceHolder.getInstance().getIdentityProviderManager().getResidentIdP(ContextLoader
                    .getTenantDomainFromContext());
        } catch (IdentityProviderManagementException e) {
            throw handleIdPException(e, Constants.ErrorMessage.ERROR_CODE_ERROR_RETRIEVING_CONFIGS, null);
        }

        // Resident Identity Provider can be null only due to an internal server error.
        if (residentIdP == null) {
            throw handleException(Response.Status.INTERNAL_SERVER_ERROR, Constants.ErrorMessage
                    .ERROR_CODE_ERROR_RETRIEVING_CONFIGS, null);
        }
        return residentIdP;
    }

    private ServiceProvider getResidentApplication() {

        ServiceProvider residentSP;
        try {
            residentSP = ConfigsServiceHolder.getInstance().getApplicationManagementService()
                    .getServiceProvider(ApplicationConstants.LOCAL_SP, ContextLoader.getTenantDomainFromContext());
        } catch (IdentityApplicationManagementException e) {
            throw handleApplicationMgtException(e, Constants.ErrorMessage.ERROR_CODE_ERROR_RETRIEVING_CONFIGS, null);
        }

        // Resident Service Provider can be null only due to an internal server error.
        if (residentSP == null) {
            throw handleException(Response.Status.INTERNAL_SERVER_ERROR, Constants.ErrorMessage
                    .ERROR_CODE_ERROR_RETRIEVING_CONFIGS, null);
        }
        return residentSP;
    }

    /**
     * Create a shallow copy of the input Identity Provider.
     *
     * @param idP Identity Provider.
     * @return Clone of IDP.
     */
    private IdentityProvider createIdPClone(IdentityProvider idP) {

        try {
            return (IdentityProvider) BeanUtils.cloneBean(idP);
        } catch (IllegalAccessException | InstantiationException | InvocationTargetException | NoSuchMethodException
                e) {
            throw handleException(Response.Status.INTERNAL_SERVER_ERROR, Constants.ErrorMessage
                    .ERROR_CODE_ERROR_UPDATING_CONFIGS, null);
        }
    }

    /**
     * Create a shallow copy of the input Service Provider.
     *
     * @param serviceProvider Service Provider.
     * @return Clone of Application.
     */
    private ServiceProvider createApplicationClone(ServiceProvider serviceProvider) {

        try {
            return (ServiceProvider) BeanUtils.cloneBean(serviceProvider);
        } catch (IllegalAccessException | InstantiationException | InvocationTargetException | NoSuchMethodException
                e) {
            throw handleException(Response.Status.INTERNAL_SERVER_ERROR, Constants.ErrorMessage
                    .ERROR_CODE_ERROR_UPDATING_CONFIGS, null);
        }
    }

    /**
     * Handle IdentityApplicationManagementException, extract error code, error description and status code to be sent
     * in the response.
     *
     * @param e         IdentityApplicationManagementException
     * @param errorEnum Error Message information.
     * @return APIError.
     */
    private APIError handleApplicationMgtException(IdentityApplicationManagementException e,
                                                   Constants.ErrorMessage errorEnum, String data) {

        ErrorResponse errorResponse = getErrorBuilder(errorEnum, data).build(log, e, errorEnum.description());

        Response.Status status;

        if (e instanceof IdentityApplicationManagementClientException) {
            if (e.getErrorCode() != null) {
                String errorCode = e.getErrorCode();
                errorCode =
                        errorCode.contains(org.wso2.carbon.identity.api.server.common.Constants.ERROR_CODE_DELIMITER) ?
                                errorCode : Constants.CONFIG_PREFIX + errorCode;
                errorResponse.setCode(errorCode);
            }
            errorResponse.setDescription(e.getMessage());
            status = Response.Status.BAD_REQUEST;
        } else if (e instanceof IdentityApplicationManagementServerException) {
            if (e.getErrorCode() != null) {
                String errorCode = e.getErrorCode();
                errorCode =
                        errorCode.contains(org.wso2.carbon.identity.api.server.common.Constants.ERROR_CODE_DELIMITER) ?
                                errorCode : Constants.CONFIG_PREFIX + errorCode;
                errorResponse.setCode(errorCode);
            }
            errorResponse.setDescription(e.getMessage());
            status = Response.Status.INTERNAL_SERVER_ERROR;
        } else {
            status = Response.Status.INTERNAL_SERVER_ERROR;
        }
        return new APIError(status, errorResponse);
    }

    /**
     * Handle IdentityProviderManagementException, extract error code, error description and status code to be sent
     * in the response.
     *
     * @param e         IdentityProviderManagementException.
     * @param errorEnum Error Message information.
     * @return APIError.
     */
    private APIError handleIdPException(IdentityProviderManagementException e,
                                        Constants.ErrorMessage errorEnum, String data) {

        ErrorResponse errorResponse = getErrorBuilder(errorEnum, data).build(log, e, errorEnum.description());

        Response.Status status;

        if (e instanceof IdentityProviderManagementClientException) {
            if (e.getErrorCode() != null) {
                String errorCode = e.getErrorCode();
                errorCode =
                        errorCode.contains(org.wso2.carbon.identity.api.server.common.Constants.ERROR_CODE_DELIMITER) ?
                                errorCode : Constants.CONFIG_PREFIX + errorCode;
                errorResponse.setCode(errorCode);
            }
            errorResponse.setDescription(e.getMessage());
            status = Response.Status.BAD_REQUEST;
        } else if (e instanceof IdentityProviderManagementServerException) {
            if (e.getErrorCode() != null) {
                String errorCode = e.getErrorCode();
                errorCode =
                        errorCode.contains(org.wso2.carbon.identity.api.server.common.Constants.ERROR_CODE_DELIMITER) ?
                                errorCode : Constants.CONFIG_PREFIX + errorCode;
                errorResponse.setCode(errorCode);
            }
            errorResponse.setDescription(e.getMessage());
            status = Response.Status.INTERNAL_SERVER_ERROR;
        } else {
            status = Response.Status.INTERNAL_SERVER_ERROR;
        }
        return new APIError(status, errorResponse);
    }

    /**
     * Handle exceptions generated in API.
     *
     * @param status HTTP Status.
     * @param error  Error Message information.
     * @return APIError.
     */
    private APIError handleException(Response.Status status, Constants.ErrorMessage error, String data) {

        return new APIError(status, getErrorBuilder(error, data).build());
    }

    /**
     * Return error builder.
     *
     * @param errorMsg Error Message information.
     * @return ErrorResponse.Builder.
     */
    private ErrorResponse.Builder getErrorBuilder(Constants.ErrorMessage errorMsg, String data) {

        return new ErrorResponse.Builder().withCode(errorMsg.code()).withMessage(errorMsg.message())
                .withDescription(includeData(errorMsg, data));
    }

    /**
     * Include context data to error message.
     *
     * @param error Constant.ErrorMessage.
     * @param data  Context data.
     * @return Formatted error message.
     */
    private static String includeData(Constants.ErrorMessage error, String data) {

        String message;
        if (StringUtils.isNotBlank(data)) {
            message = String.format(error.description(), data);
        } else {
            message = error.description();
        }
        return message;
    }
}
