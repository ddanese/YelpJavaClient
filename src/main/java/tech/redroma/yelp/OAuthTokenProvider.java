/*
 * Copyright 2016 RedRoma, Inc..
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tech.redroma.yelp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.redroma.yelp.exceptions.YelpBadArgumentException;
import tech.redroma.yelp.exceptions.YelpOperationFailedException;
import tech.sirwellington.alchemy.annotations.arguments.NonEmpty;
import tech.sirwellington.alchemy.annotations.arguments.Required;
import tech.sirwellington.alchemy.http.AlchemyHttp;
import tech.sirwellington.alchemy.http.HttpResponse;
import tech.sirwellington.alchemy.http.exceptions.AlchemyHttpException;

import static tech.sirwellington.alchemy.arguments.Arguments.checkThat;
import static tech.sirwellington.alchemy.arguments.assertions.Assertions.notNull;
import static tech.sirwellington.alchemy.arguments.assertions.BooleanAssertions.trueStatement;
import static tech.sirwellington.alchemy.arguments.assertions.StringAssertions.nonEmptyString;
import static tech.sirwellington.alchemy.arguments.assertions.StringAssertions.stringWithLengthGreaterThan;

/**
 * The OAuthTokenProvider is responsible for providing an OAuth Token used to make API calls.
 * The recommended method is to use the {@linkplain #newRefreshingTokenProvider(java.lang.String, java.lang.String) Refereshing OAUth Provider}.
 * <p>
 * For more information on Yelp's OAuth mechanism.
 * <a href="https://www.yelp.com/developers/documentation/v3/get_started">Click here for more information</a>.
 *
 * @author SirWellington
 * @see
 * <a href="https://www.yelp.com/developers/documentation/v3/get_started">https://www.yelp.com/developers/documentation/v3/get_started</a>
 */
public interface OAuthTokenProvider
{

    /**
     * Creates an {@link OAuthTokenProvider} using the provided key. Use this if you already have an OAuth token that you want to
     * reuse.
     *
     * @param token The OAuth token to use when making API calls.
     * @return
     * @throws IllegalArgumentException
     */
    public static OAuthTokenProvider newBasicTokenProvider(@NonEmpty String token) throws IllegalArgumentException
    {
        checkThat(token).is(nonEmptyString());

        return new OAuthTokenProviderBasic(token);
    }

    /**
     * Creates a refreshing {@link OAuthTokenProvider} that obtains OAuth Tokens from Yelp and periodically renews the token
     * before it expires. This is the recommended method of authentication.
     * 
     * @param clientId     The Client ID obtained from the Yelp Developer Console.
     * @param clientSecret The Client Secret obtained from the Yelp Developer Console.
     * @return
     * @throws IllegalArgumentException
     */
    public static OAuthTokenProvider newRefreshingTokenProvider(@NonEmpty String clientId, @NonEmpty String clientSecret) throws IllegalArgumentException
    {
        final String DEFAULT_AUTH_URL = "https://api.yelp.com/oauth2/token";
        
        try
        {
            URL authURL = new URL(DEFAULT_AUTH_URL);
            return newRefeshingTokenProvider(clientId, clientSecret, authURL);
        }
        catch (MalformedURLException ex)
        {
            throw new IllegalArgumentException("Could not form Auth URL.", ex);
        }
    }

    /**
     * @param clientId     The Client ID obtained from the Yelp Developer Console.
     * @param clientSecret The Client Secret obtained from the Yelp Developer Console.
     * @param authURL
     * @return
     * @throws IllegalArgumentException
     */
    public static OAuthTokenProvider newRefeshingTokenProvider(@NonEmpty String clientId, @NonEmpty String clientSecret,
                                                               @Required URL authURL) throws IllegalArgumentException
    {
        AlchemyHttp http = AlchemyHttp.newDefaultInstance();

        return newRefreshingTokenProvider(clientId, clientSecret, authURL, http);
    }

    /**
     *
     * @param clientId     The Client ID obtained from the Yelp Developer Console.
     * @param clientSecret The Client Secret obtained from the Yelp Developer Console.
     * @param authURL      The Authentication URL to use for authentication.
     * @param http         The Alchemy HTTP Client to use during requests.
     * @return
     * @throws IllegalArgumentException
     * @see #newRefreshingTokenProvider(java.lang.String, java.lang.String, java.net.URL,
     * tech.sirwellington.alchemy.http.AlchemyHttp)
     */
    static OAuthTokenProvider newRefreshingTokenProvider(@NonEmpty String clientId,
                                                         @NonEmpty String clientSecret,
                                                         @Required URL authURL,
                                                         @Required AlchemyHttp http) throws IllegalArgumentException
    {
        checkThat(clientId, clientSecret)
            .usingMessage("cliend ID and client secret are required")
            .are(nonEmptyString());

        checkThat(authURL)
            .usingMessage("Authorization URL is required")
            .is(notNull());

        checkThat(http)
            .usingMessage("Alchemy HTTP cannot be null")
            .is(notNull());

        return new OAuthObtainer(http, authURL, clientId, clientSecret);
    }

    /**
     * Obtain an OAuth token used for API calls.
     *
     * @return
     */
    @Required
    String getToken();

    static OAuthTokenProvider basicTokenProviderWithToken(@NonEmpty String token) throws IllegalArgumentException
    {
        checkThat(token)
            .usingMessage("token is required")
            .is(nonEmptyString());

        return new OAuthTokenProviderBasic(token);
    }


    static final class OAuthObtainer implements OAuthTokenProvider
    {

        private final AlchemyHttp http;
        private final URL authenticationURL;
        private final String clientId;
        private final String clientSecret;

        private static final int BAD_REQUEST = 400;

        private static final Logger LOG = LoggerFactory.getLogger(OAuthObtainer.class);

        OAuthObtainer(AlchemyHttp http, URL authenticationURL, String clientId, String clientSecret)
        {
            checkThat(http, authenticationURL)
                .are(notNull());

            checkThat(clientId, clientSecret)
                .are(nonEmptyString())
                .are(stringWithLengthGreaterThan(2));

            this.http = http;
            this.authenticationURL = authenticationURL;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }

        @Override
        public String getToken()
        {
            JsonObject response = null;

            try
            {
                response = http.go()
                    .post()
                    .nothing()
                    .usingQueryParam(Keys.GRANT_TYPE, "client_credentials")
                    .usingQueryParam(Keys.CLIENT_ID, clientId)
                    .usingQueryParam(Keys.CLIENT_SECRET, clientSecret)
                    .usingHeader(Keys.CONTENT_TYPE, Keys.URL_ENCODED)
                    .expecting(JsonObject.class)
                    .at(authenticationURL);
            }
            catch (AlchemyHttpException ex)
            {
                HttpResponse yelpResponse = ex.getResponse();
                int status = yelpResponse.statusCode();

                if (status == BAD_REQUEST)
                {
                    throw new YelpBadArgumentException("Client ID or Secret are incorrect: " + yelpResponse.body());
                }

                throw new YelpOperationFailedException("Failed to get access token.", ex);
            }

            checkResponse(response);
            printExpiration(response);

            return tryToGetTokenFrom(response);
        }

        @Override
        public int hashCode()
        {
            int hash = 7;
            hash = 79 * hash + Objects.hashCode(this.http);
            hash = 79 * hash + Objects.hashCode(this.authenticationURL);
            return hash;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
            {
                return true;
            }
            if (obj == null)
            {
                return false;
            }
            if (getClass() != obj.getClass())
            {
                return false;
            }
            final OAuthObtainer other = (OAuthObtainer) obj;
            if (!Objects.equals(this.http, other.http))
            {
                return false;
            }
            if (!Objects.equals(this.authenticationURL, other.authenticationURL))
            {
                return false;
            }
            return true;
        }

        @Override
        public String toString()
        {
            return "OAuthObtainer{" + "http=" + http + ", authenticationURL=" + authenticationURL + '}';
        }

        private void printExpiration(JsonObject response)
        {
            JsonElement expiration = response.get(Keys.EXPIRATION);

            if (expiration.isJsonPrimitive())
            {
                JsonPrimitive expirationValue = expiration.getAsJsonPrimitive();

                if (expirationValue.isNumber())
                {
                    int expirationSeconds = expirationValue.getAsInt();

                    long expirationDays = TimeUnit.SECONDS.toDays(expirationSeconds);
                    long expirationMinutes = TimeUnit.SECONDS.toMinutes(expirationSeconds);
                    LOG.debug("Yelp Token expires in {} days or {} minutes", expirationDays, expirationMinutes);
                }
                else
                {
                    LOG.warn("Received unexpected token expiration: " + expirationValue);
                }

            }
        }

        private void checkResponse(JsonObject response)
        {
            checkThat(response)
                .throwing(YelpOperationFailedException.class)
                .usingMessage("Received unexpected null response")
                .is(notNull());

            checkThat(response.has(Keys.EXPIRATION))
                .usingMessage("OAuth response is missing expiration information: " + response)
                .is(trueStatement());

            checkThat(response.has(Keys.TOKEN))
                .usingMessage("OAUth response is missing access token: " + response)
                .throwing(YelpOperationFailedException.class)
                .is(trueStatement());
        }

        private String tryToGetTokenFrom(JsonObject response)
        {
            checkThat(response.has(Keys.TOKEN))
                .usingMessage("Yelp AUTH response is missing the token")
                .throwing(YelpBadArgumentException.class)
                .is(trueStatement());

            return response.get(Keys.TOKEN).getAsString();
        }

        private static class Keys
        {

            static final String GRANT_TYPE = "grant_type";
            static final String CLIENT_ID = "client_id";
            static final String CLIENT_SECRET = "client_secret";
            static final String CONTENT_TYPE = "Content-Type";
            static final String URL_ENCODED = "application/x-www-form-urlencoded";

            //Response Keys
            static final String EXPIRATION = "expires_in";
            static final String TOKEN = "access_token";
        }

    }

}