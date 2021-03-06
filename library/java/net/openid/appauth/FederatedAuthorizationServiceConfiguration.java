/*
 * Copyright 2015 The AppAuth for Android Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openid.appauth;

import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.util.Log;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import net.openid.appauth.AuthorizationException.GeneralErrors;
import net.openid.appauth.connectivity.ConnectionBuilder;
import net.openid.appauth.internal.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static net.openid.appauth.Preconditions.checkNotNull;

/**
 * Configuration details required to interact with an authorization service.
 */
public class FederatedAuthorizationServiceConfiguration extends AuthorizationServiceConfiguration {
    /**
     * Creates an service configuration for an OpenID Connect provider, based on its
     * {@link AuthorizationServiceDiscovery discovery document}.
     *
     * @param discoveryDoc The OpenID Connect discovery document which describes this service.
     */
    public FederatedAuthorizationServiceConfiguration(
            @NonNull AuthorizationServiceDiscovery discoveryDoc) {
        super(discoveryDoc);
    }

    /**
     * Fetch a AuthorizationServiceConfiguration from an OpenID Connect discovery URI.
     *
     * @param openIdConnectDiscoveryUri The OpenID Connect discovery URI
     * @param connectionBuilder The connection builder that is used to establish a connection
     *     to the resource server.
     * @param callback A callback to invoke upon completion
     * @param authorized_keys A JSONObject representing a JWKS with the authorized_keys for
     *                        federation support
     *
     * @see "OpenID Connect discovery 1.0
     * <https://openid.net/specs/openid-connect-discovery-1_0.html>"
     */
    public static void fetchFromUrl(
        @NonNull Uri openIdConnectDiscoveryUri,
        @NonNull RetrieveConfigurationCallback callback,
        @NonNull ConnectionBuilder connectionBuilder,
        @NonNull JSONObject authorized_keys) {
        checkNotNull(openIdConnectDiscoveryUri, "openIDConnectDiscoveryUri cannot be null");
        checkNotNull(callback, "callback cannot be null");
        checkNotNull(connectionBuilder, "connectionBuilder must not be null");
        checkNotNull(authorized_keys, "authorized_keys must not be null");
        new ConfigurationRetrievalAsyncTask(
            openIdConnectDiscoveryUri,
            connectionBuilder,
            authorized_keys,
            callback)
            .execute();
    }

    /**
     * ASyncTask that tries to retrieve the discover document and gives the callback with the
     * values retrieved from the discovery document. In case of retrieval error, the exception
     * is handed back to the callback.
     */
    private static class ConfigurationRetrievalAsyncTask
            extends AsyncTask<Void, Void, FederatedAuthorizationServiceConfiguration> {

        private Uri mUri;
        private ConnectionBuilder mConnectionBuilder;
        private RetrieveConfigurationCallback mCallback;
        private AuthorizationException mException;
        private JSONObject mAuthorizedKeys;

        ConfigurationRetrievalAsyncTask(
                Uri uri,
                ConnectionBuilder connectionBuilder,
                JSONObject authorized_keys,
                RetrieveConfigurationCallback callback) {
            mUri = uri;
            mConnectionBuilder = connectionBuilder;
            mCallback = callback;
            mAuthorizedKeys = authorized_keys;
            mException = null;
        }

        /**
         * Indicates whether an object is a subset of another one, according to the OIDC Federation
         * draft.
         * @param obj1 One object.
         * @param obj2 Another object.
         * @return True if obj1 is a subset of obj2. False otherwise.
         * @throws JSONException when the objects have an unexpected type.
         */
        private boolean is_subset(Object obj1, Object obj2) throws JSONException {
            if (!obj1.getClass().equals(obj2.getClass()))
                return false;
            else if (obj1 instanceof String)
                return obj1.equals(obj2);
            else if (obj1 instanceof Integer)
                return (Integer) obj1 <= (Integer) obj2;
            else if (obj1 instanceof Double)
                return (Double) obj1 <= (Double) obj2;
            else if (obj1 instanceof Long)
                return (Long) obj1 <= (Long) obj2;
            else if (obj1 instanceof Boolean)
                return obj1 == obj2;
            else if (obj1 instanceof JSONArray){
                JSONArray list1 = (JSONArray) obj1;
                JSONArray list2 = (JSONArray) obj2;
                for (int i=0; i<list1.length(); i++){
                    boolean found = false;
                    for (int j=0; j<list2.length(); j++){
                        if (list1.get(i).equals(list2.get(j))) {
                            found = true;
                            break;
                        }
                    }
                    if (!found)
                        return false;
                }
                return true;
            }
            else if (obj1 instanceof JSONObject){
                JSONObject jobj1 = (JSONObject) obj1;
                JSONObject jobj2 = (JSONObject) obj2;
                for(Iterator<String> iter = jobj1.keys(); iter.hasNext();) {
                    String key = iter.next();
                    if (!jobj2.has(key) || !is_subset(jobj1.get(key), jobj2.get(key)))
                        return false;
                }
                return true;
            }
            else
                throw new JSONException("Unexpected JSON class: " + obj1.getClass().toString());
        }

        /**
         * Flatten two metadata statements into one, following the rules from
         * the OIDC federation draft.
         * @param upper MS (n)
         * @param lower MS(n-1)
         * @return A flattened version of both statements.
         * @throws JSONException when upper MS tries to overwrite lower MS breaking the policies
         * from the OIDC federation draft.
         */
        private JSONObject flatten(JSONObject upper, JSONObject lower) throws JSONException {
            String[] use_lower = {"iss", "sub", "aud", "exp", "nbf", "iat", "jti"};
            String[] use_upper = {"signing_keys", "signing_keys_uri", "metadata_statement_uris",
                                  "kid", "metadata_statements", "usage"};
            List<String> use_lower_list = Arrays.asList(use_lower);
            List<String> use_upper_list = Arrays.asList(use_upper);

            /* result starts as a copy of lower MS */
            JSONObject flattened = new JSONObject(lower.toString());
            for(Iterator<String> iter = upper.keys(); iter.hasNext();) {
                String claim_name = iter.next();
                if (use_lower_list.contains(claim_name))
                    continue;

                /* If the claim does not exist on lower, or it is marked as "use_upper", or is a
                   subset of lower, then use upper's one -> OK */
                if (lower.opt(claim_name) == null
                        || use_upper_list.contains(claim_name)
                        || is_subset(upper.get(claim_name), lower.get(claim_name))) {
                    flattened.put(claim_name, upper.get(claim_name));
                }

                /* Else -> policy breach */
                else {
                    throw new JSONException("Policy breach with claim: " + claim_name
                        + ". Lower value=" + lower.get(claim_name)
                        + ". Upper value=" + upper.get(claim_name));
                }
            }
            return flattened;
        }

        /**
         * Verifies the signature of a JWT using the indicated keys.
         * @param signedJWT Signed JWT
         * @param keys Keys that can be used to verify the token
         * @throws BadJOSEException when the JWT is not valid
         * @throws JOSEException when the signature cannot be validated
         */
        private void verify_signature(SignedJWT signedJWT, JWKSet keys)
                throws BadJOSEException, JOSEException {
            ConfigurableJWTProcessor jwtProcessor = new DefaultJWTProcessor();
            JWSKeySelector keySelector = new JWSVerificationKeySelector(
                signedJWT.getHeader().getAlgorithm(),
                new ImmutableJWKSet(keys));
            DefaultJWTClaimsVerifier cverifier = new DefaultJWTClaimsVerifier();
            /* Allow some clock skew as testing platform examples are static */
            cverifier.setMaxClockSkew(50000000);
            jwtProcessor.setJWTClaimsSetVerifier(cverifier);
            jwtProcessor.setJWSKeySelector(keySelector);
            jwtProcessor.process(signedJWT, null);
        }

        /**
         * Collects inner metadata statement for a specific FO
         * @param payload Metadata statement containing inner metadata statements
         * @return A MS for the specified FO. Null if not found
         * @throws IOException when a "metadata_statement_uris" key cannot be downloaded
         * @throws JSONException when a JSON exception occurs
         */
        private String get_metadata_statement(JSONObject payload, String fed_op)
                throws IOException, JSONException {
            JSONObject ms = payload.optJSONObject("metadata_statements");
            JSONObject ms_uris = payload.optJSONObject("metadata_statement_uris");
            if (ms != null && ms.has(fed_op))
                return ms.getString(fed_op);
            if (ms_uris != null && ms_uris.has(fed_op)) {
                Log.d("FED", "Getting MS from " + JsonUtil.getUri(ms_uris, fed_op));
                HttpURLConnection conn = mConnectionBuilder.openConnection(
                    JsonUtil.getUri(ms_uris, fed_op));
                conn.setRequestMethod("GET");
                conn.setDoInput(true);
                conn.connect();
                InputStream is = conn.getInputStream();
                return Utils.readInputStream(is);
            }
            return null;
        }

        /**
         * Decodes, verifies and flattens a compounded MS for a specific federation operator
         * @param ms_jwt Encoded JWT representing a signed metadata statement
         * @return A JSONObject (dict) with a entry per federation operator with the corresponding
         * flattened and verified MS
         * @throws IOException Thrown when some network resource could not be obtained
         */
        private JSONObject verify_ms(String ms_jwt, String fed_op)
                throws JSONException, BadJOSEException, JOSEException, ParseException, IOException {
            try {
                /* Parse the signed JWT */
                SignedJWT signedJWT = SignedJWT.parse(ms_jwt);

                /* Create an empty JWKS to store gathered keys from the inner MSs */
                JWKSet keys = new JWKSet();

                /* Convert nimbus JSON object to org.json.JSONObject for simpler processing */
                JSONObject payload = new JSONObject(signedJWT.getPayload().toString());

                Log.d("FED", "Inspecting MS signed by: " + payload.getString("iss")
                    + " with KID:" + signedJWT.getHeader().getKeyID());

                /* Collect inner MS (JWT encoded) */
                String inner_ms_jwt = get_metadata_statement(payload, fed_op);

                /* This will hold the result of the verification/decoding/flattening */
                JSONObject result;

                /* If there are more MSs, recursively analyzed them and return the flattened version
                   with the inner payload */
                if (inner_ms_jwt != null) {
                    /* Recursion here to get a verified and flattened version of inner_ms */
                    JSONObject inner_ms_flattened = verify_ms(inner_ms_jwt, fed_op);

                    /* add signing keys */
                    JWKSet inner_ms_sigkeys = JWKSet.parse(
                        inner_ms_flattened.getJSONObject("signing_keys").toString());
                    keys.getKeys().addAll(inner_ms_sigkeys.getKeys());
                    result = flatten(payload, inner_ms_flattened);
                }
                /* If there are no inner metadata statements, this is MS0 and root keys must
                   be used for validating the signature.
                   Result will be the decoded payload */
                else {
                    keys = JWKSet.parse(this.mAuthorizedKeys.getJSONObject(fed_op).toString());
                    result = payload;
                }

                /* verify the signature using the collected keys */
                verify_signature(signedJWT, keys);
                Log.d("FED", "Successful validation of signature of " + payload.getString("iss")
                    + " with KID:" + signedJWT.getHeader().getKeyID());
                return result;
            }
            /* In case of any error, print a log message and let the exception flow */
            catch (JOSEException | JSONException | ParseException | IOException | BadJOSEException e) {
                Log.d("FED", "Error validating MS. Ignoring. " + e.toString());
                throw e;
            }
        }

        /**
         * Given a discovery document, try to get a federated/signed version of it
         * @param discovery_doc Discovery document as retrieved from .well-known/openid-configuration
         * @return A discovery document which has been validated using a supported federation
         */
        private JSONObject getFederatedConfiguration(JSONObject discovery_doc) {
            try {
                // Get the inner metadata statement for the first trusted FO
                for (Iterator<String> it = this.mAuthorizedKeys.keys(); it.hasNext();){
                    String fed_op = it.next();
                    String ms_jwt = get_metadata_statement(discovery_doc, fed_op);
                    if (ms_jwt != null) {
                        JSONObject ms_flattened = verify_ms(ms_jwt, fed_op);
                        Log.d("FED", "Statement for federation id " + fed_op);
                        System.out.println(ms_flattened.toString(2));
                        return ms_flattened;
                    }
                }

                Log.d("FED", "There are no metadata_statements for any trusted FO");
                JSONObject metadata_statements = discovery_doc.optJSONObject("metadata_statements");
                JSONObject metadata_statement_uris = discovery_doc.optJSONObject("metadata_statement_uris");
                if (metadata_statements != null || metadata_statement_uris != null) {
                    Log.d("FED", "There are statements for other FOs");
                    for (Iterator<String> it = metadata_statements.keys(); it.hasNext();)
                        Log.d("FED", "FO: " + it.next());
                    for (Iterator<String> it = metadata_statement_uris.keys(); it.hasNext();)
                        Log.d("FED", "FO (uri): " + it.next());
                }
            } catch (JOSEException | IOException | JSONException | ParseException
                     | BadJOSEException e) {
                Log.d("FED", "There was a problem validating the federated metadata: " +
                    e.toString());
            }
            return null;
        }

        @Override
        protected FederatedAuthorizationServiceConfiguration doInBackground(Void... voids) {
            InputStream is = null;
            try {
                HttpURLConnection conn = mConnectionBuilder.openConnection(mUri);
                conn.setRequestMethod("GET");
                conn.setDoInput(true);
                conn.connect();

                is = conn.getInputStream();
                JSONObject json = new JSONObject(Utils.readInputStream(is));

                JSONObject mss = getFederatedConfiguration(json);
                if (mss != null)
                    json = mss;

                AuthorizationServiceDiscovery discovery =
                        new AuthorizationServiceDiscovery(json);

                return new FederatedAuthorizationServiceConfiguration(discovery);
            } catch (IOException ex) {
                Logger.errorWithStack(ex, "Network error when retrieving discovery document");
                mException = AuthorizationException.fromTemplate(
                        GeneralErrors.NETWORK_ERROR,
                        ex);
            } catch (JSONException ex) {
                Logger.errorWithStack(ex, "Error parsing discovery document");
                mException = AuthorizationException.fromTemplate(
                        GeneralErrors.JSON_DESERIALIZATION_ERROR,
                        ex);
            } catch (AuthorizationServiceDiscovery.MissingArgumentException ex) {
                Logger.errorWithStack(ex, "Malformed discovery document");
                mException = AuthorizationException.fromTemplate(
                        GeneralErrors.INVALID_DISCOVERY_DOCUMENT,
                        ex);
            } finally {
                Utils.closeQuietly(is);
            }
            return null;
        }

        @Override
        protected void onPostExecute(FederatedAuthorizationServiceConfiguration configuration) {
            if (mException != null) {
                mCallback.onFetchConfigurationCompleted(null, mException);
            } else {
                mCallback.onFetchConfigurationCompleted(configuration, null);
            }
        }
    }
}
