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
package org.apache.ofbiz.webapp.control;

import java.security.Key;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.spec.SecretKeySpec;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.xml.bind.DatatypeConverter;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilHttp;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.DelegatorFactory;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityUtilProperties;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.webapp.WebAppUtil;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * This class manages the authentication tokens that provide single sign-on authentication to the OFBiz applications.
 */
public class ExternalLoginKeysManager {
    private static final String module = ExternalLoginKeysManager.class.getName();
    private static final String EXTERNAL_LOGIN_KEY_ATTR = "externalLoginKey";
    // This Map is keyed by the randomly generated externalLoginKey and the value is a UserLogin GenericValue object
    private static final Map<String, GenericValue> externalLoginKeys = new ConcurrentHashMap<>();
    public static final String EXTERNAL_SERVER_LOGIN_KEY = "externalServerLoginKey";
    // This works the same way than externalLoginKey but between 2 servers, not 2 webapps on the same server. 
    // The Single Sign On (SSO) is ensured by a JWT token, then all is handled as normal by a session on the reached server. 
    // The servers may or may not share a database but the 2 loginUserIds must be the same.
    
    // OOTB the JWT masterSecretKey is not properly initialised and can not be OOTB.
    // As we sign on on several servers, so have different sessions, we can't use the externalLoginKey way to create the JWT masterSecretKey.
    // IMO the best way to create the JWT masterSecretKey is to use a temporary way to load in a static final key when compiling. 
    // This is simple and most secure. See OFBIZ-9833 for more, notably https://s.apache.org/cFeK
    
    // Because it will contain the ExternalServerJwtMasterSecretKey value; 
    // you should not let the ExternalLoginKeysManager.java file on a production server after its compilation 
    private static final String ExternalServerJwtMasterSecretKey = "ExternalServerJwtMasterSecretKey";

    /**
     * Gets (and creates if necessary) an authentication token to be used for an external login parameter.
     * When a new token is created, it is persisted in the web session and in the web request and map entry keyed by the
     * token and valued by a userLogin object is added to a map that is looked up for subsequent requests.
     *
     * @param request - the http request in which the authentication token is searched and stored
     * @return the authentication token as persisted in the session and request objects
     */
    public static String getExternalLoginKey(HttpServletRequest request) {
        String externalKey = (String) request.getAttribute(EXTERNAL_LOGIN_KEY_ATTR);
        if (externalKey != null) return externalKey;

        HttpSession session = request.getSession();
        synchronized (session) {
            // if the session has a previous key in place, remove it from the master list
            String sesExtKey = (String) session.getAttribute(EXTERNAL_LOGIN_KEY_ATTR);

            if (sesExtKey != null) {
                if (isAjax(request)) return sesExtKey;

                externalLoginKeys.remove(sesExtKey);
            }

            GenericValue userLogin = (GenericValue) request.getAttribute("userLogin");
            //check the userLogin here, after the old session setting is set so that it will always be cleared
            if (userLogin == null) return "";

            //no key made yet for this request, create one
            while (externalKey == null || externalLoginKeys.containsKey(externalKey)) {
                UUID uuid = UUID.randomUUID();
                externalKey = "EL" + uuid.toString();
            }

            request.setAttribute(EXTERNAL_LOGIN_KEY_ATTR, externalKey);
            session.setAttribute(EXTERNAL_LOGIN_KEY_ATTR, externalKey);
            externalLoginKeys.put(externalKey, userLogin);
            return externalKey;
        }
    }

    /**
     * Removes the authentication token, if any, from the session.
     *
     * @param session - the http session from which the authentication token is removed
     */
    static void cleanupExternalLoginKey(HttpSession session) {
        String sesExtKey = (String) session.getAttribute(EXTERNAL_LOGIN_KEY_ATTR);
        if (sesExtKey != null) {
            externalLoginKeys.remove(sesExtKey);
        }
    }

    /**
     * OFBiz controller event that performs the user authentication using the authentication token.
     * The methods is designed to be used in a chain of controller preprocessor event: it always return &amp;success&amp;
     * even when the authentication token is missing or the authentication fails in order to move the processing to the
     * next event in the chain.
     *
     * @param request - the http request object
     * @param response - the http response object
     * @return - &amp;success&amp; in all the cases
     */
    public static String checkExternalLoginKey(HttpServletRequest request, HttpServletResponse response) {
        String externalKey = request.getParameter(EXTERNAL_LOGIN_KEY_ATTR);
        if (externalKey == null) return "success";

        GenericValue userLogin = externalLoginKeys.get(externalKey);
        if (userLogin != null) {
            //to check it's the right tenant
            //in case username and password are the same in different tenants
            Delegator delegator = (Delegator) request.getAttribute("delegator");
            String oldDelegatorName = delegator.getDelegatorName();
            if (!oldDelegatorName.equals(userLogin.getDelegator().getDelegatorName())) {
                delegator = DelegatorFactory.getDelegator(userLogin.getDelegator().getDelegatorName());
                LocalDispatcher dispatcher = WebAppUtil.makeWebappDispatcher(request.getServletContext(), delegator);
                LoginWorker.setWebContextObjects(request, response, delegator, dispatcher);
            }
            // found userLogin, do the external login...

            // if the user is already logged in and the login is different, logout the other user
            HttpSession session = request.getSession();
            GenericValue currentUserLogin = (GenericValue) session.getAttribute("userLogin");
            if (currentUserLogin != null) {
                if (currentUserLogin.getString("userLoginId").equals(userLogin.getString("userLoginId"))) {
                    // is the same user, just carry on...
                    return "success";
                }

                // logout the current user and login the new user...
                LoginWorker.logout(request, response);
                // ignore the return value; even if the operation failed we want to set the new UserLogin
            }

            LoginWorker.doBasicLogin(userLogin, request);
        } else {
            Debug.logWarning("Could not find userLogin for external login key: " + externalKey, module);
        }

        return "success";
    }

    private static boolean isAjax(HttpServletRequest request) {
       return "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
    }
    
    public static String externalServerLoginCheck(HttpServletRequest request, HttpServletResponse response) {

        Delegator delegator = (Delegator) request.getAttribute("delegator");
        HttpSession session = request.getSession();

        String externalServerUserLoginId = request.getParameter(EXTERNAL_SERVER_LOGIN_KEY);
        if (externalServerUserLoginId == null) return "success"; // Nothing to do here

        GenericValue currentUserLogin = (GenericValue) session.getAttribute("userLogin");

        try {
            GenericValue userLogin = EntityQuery.use(delegator).from("UserLogin").where("userLoginId", externalServerUserLoginId).queryOne();
            if (userLogin != null) {
                //to check it's the right tenant
                //in case username and password are the same in different tenants
                LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
                delegator = (Delegator) request.getAttribute("delegator");
                String oldDelegatorName = delegator.getDelegatorName();
                ServletContext servletContext = session.getServletContext();
                if (!oldDelegatorName.equals(userLogin.getDelegator().getDelegatorName())) {
                    delegator = DelegatorFactory.getDelegator(userLogin.getDelegator().getDelegatorName());
                    dispatcher = WebAppUtil.makeWebappDispatcher(servletContext, delegator);
                    LoginWorker.setWebContextObjects(request, response, delegator, dispatcher);
                }

                String authorisationHeader = request.getHeader("Authorisation");
                if (authorisationHeader != null) {
                    boolean jwtOK = checkJwt(authorisationHeader, userLogin.getString("userLoginId"), getExternalServerName(request), UtilHttp.getApplicationName(request));
                    if (!jwtOK) {
                        Debug.logWarning("*** There was a problem with the JWT token, loging out the current user: " + externalServerUserLoginId, module);
                        LoginWorker.logout(request, response);
                        return "success";
                    }
                } else {
                    // Something weird happened here => logout current user
                    Debug.logWarning("*** There was a problem with the JWT token, loging out the current user: " + externalServerUserLoginId, module);
                    LoginWorker.logout(request, response);
                    return "success";
                }

                // if the user is already logged in and the login is different, logout the other user
                if (currentUserLogin != null) {
                    if (currentUserLogin.getString("userLoginId").equals(userLogin.getString("userLoginId"))) {
                        // is the same user, just carry on...
                        return "success";
                    }

                    // logout the current user and login the new user...
                    LoginWorker.logout(request, response);
                    // ignore the return value; even if the operation failed we want to set the new UserLogin
                }

                //connect
                String enabled = userLogin.getString("enabled");
                if (enabled == null || "Y".equals(enabled)) {
                    userLogin.set("hasLoggedOut", "N");
                    userLogin.store();
                }
                LoginWorker.doBasicLogin(userLogin, request);
            } else {
                Debug.logWarning("Could not find userLogin for external login key: " + externalServerUserLoginId, module);
            }
        } catch (GenericEntityException e) {
            Debug.logError(e, "Cannot get autoUserLogin information: " + e.getMessage(), module);
        }

        return "success";
    }
    
    /**
     * Generate and return a JWT key
     * 
     * @param id is an Id, I suggest userLoginId
     * @param issuer is who/what issued the token. I suggest the server DNS
     * @param subject is the subject of the token. I suggest the destination webapp
     * @param ttlMillis the expiration time
     * @return a JWT token
     */
    public static String createJwt(String id, String issuer, String subject, long ttlMillis) {
        //The JWT signature algorithm we will be using to sign the token
        SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.HS512;

        long nowMillis = System.currentTimeMillis();
        Date now = new Date(nowMillis);

        byte[] apiKeySecretBytes = DatatypeConverter.parseBase64Binary(ExternalServerJwtMasterSecretKey);
        Key signingKey = new SecretKeySpec(apiKeySecretBytes, signatureAlgorithm.getJcaName());
        //Let's set the JWT Claims
        JwtBuilder builder = Jwts.builder().setId(id)
                                    .setIssuedAt(now)
                                    .setSubject(subject)
                                    .setIssuer(issuer)
                                    .setIssuedAt(now)
                                    .signWith(signatureAlgorithm, signingKey);

        //if it has been specified, let's add the expiration date, this should always be true
        if (ttlMillis >= 0) {
            long expMillis = nowMillis + ttlMillis;
            Date exp = new Date(expMillis);
            builder.setExpiration(exp);
        }

        //Builds the JWT and serialises it to a compact, URL-safe string
        return builder.compact();
    }
    
    /**
     * Reads and validates a JWT token
     * Throws a SignatureException if it is not a signed JWS (as expected) or has been tampered
     * @param jwt a JWT token
     * @param id is an Id, I suggest userLoginId
     * @param issuer is who/what issued the token. I suggest the server DNS
     * @param subject is the subject of the token. I suggest the destination webapp
     * @return true if the JWT token corresponds to the one sent and is not expired
     */
    private static boolean checkJwt(String jwt, String id, String issuer, String subject) {
        //The JWT signature algorithm is using this to sign the token
        SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.HS512;

        byte[] apiKeySecretBytes = DatatypeConverter.parseBase64Binary(ExternalServerJwtMasterSecretKey);
        Key signingKey = new SecretKeySpec(apiKeySecretBytes, signatureAlgorithm.getJcaName());

        //This line will throw a SignatureException if it is not a signed JWS (as expected) or has been tampered
        Claims claims = Jwts.parser()
           .setSigningKey(signingKey)
           .parseClaimsJws(jwt).getBody();

        long nowMillis = System.currentTimeMillis();
        Date now = new Date(nowMillis);

        return claims.getId().equals(id) 
                && claims.getIssuer().equals(issuer)
                && claims.getSubject().equals(subject)
                && claims.getExpiration().after(now);
    }

    public static String getExternalServerName(HttpServletRequest request) {
        String reportingServerName = "";
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        if (delegator != null && "Y".equals(EntityUtilProperties.getPropertyValue("security", "use-external-server", "Y", delegator))) {
            reportingServerName = EntityUtilProperties.getPropertyValue("security", "external-server-name", "localhost:8443", delegator);
            String reportingServerQuery = EntityUtilProperties.getPropertyValue("security", "external-server-query", "/catalog/control/", delegator);
            reportingServerName = "https://" + reportingServerName + reportingServerQuery;
        }
        return reportingServerName;
    }
    
    public static long getJwtTokenTimeToLive(HttpServletRequest request) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        if (delegator != null) return 1000 * Long.parseLong(EntityUtilProperties.getPropertyValue("security", "external-server-token-duration", "30", delegator));
        else return 1000 * 30;
    }

}
