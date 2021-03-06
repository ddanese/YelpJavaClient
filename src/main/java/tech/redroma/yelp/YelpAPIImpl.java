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

import java.net.MalformedURLException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sir.wellington.alchemy.collections.lists.Lists;
import tech.redroma.yelp.exceptions.YelpAuthenticationException;
import tech.redroma.yelp.exceptions.YelpBadArgumentException;
import tech.redroma.yelp.exceptions.YelpException;
import tech.redroma.yelp.exceptions.YelpOperationFailedException;
import tech.sirwellington.alchemy.annotations.access.Internal;
import tech.sirwellington.alchemy.annotations.access.NonInstantiable;
import tech.sirwellington.alchemy.http.AlchemyHttp;
import tech.sirwellington.alchemy.http.AlchemyRequest;
import tech.sirwellington.alchemy.http.exceptions.AlchemyHttpException;

import static java.lang.String.format;
import static tech.sirwellington.alchemy.arguments.Arguments.checkThat;
import static tech.sirwellington.alchemy.arguments.assertions.Assertions.notNull;
import static tech.sirwellington.alchemy.arguments.assertions.NetworkAssertions.validURL;
import static tech.sirwellington.alchemy.arguments.assertions.StringAssertions.nonEmptyString;

/**
 * This internal class is responsible for implementing the business logic necessary for making Yelp API Queries. It implements all
 * of the operations defined in the {@link YelpAPI}.
 *
 * @author SirWellington
 */
@Internal
final class YelpAPIImpl implements YelpAPI
{
    
    private final static Logger LOG = LoggerFactory.getLogger(YelpAPIImpl.class);
    
    private final AlchemyHttp http;
    private final String baseURL;
    private String apiKey;
    
    YelpAPIImpl(AlchemyHttp http, String apiKey, String baseURL)
    {
        checkThat(http, apiKey)
            .are(notNull());

        checkThat(baseURL)
            .is(nonEmptyString())
            .is(validURL());

        this.http = http;
        this.apiKey = apiKey;
        this.baseURL = baseURL;
    }

    @Override
    public YelpBusinessDetails getBusinessDetails(String businessId) throws YelpException
    {
        checkThat(businessId)
            .throwing(YelpBadArgumentException.class)
            .usingMessage("Business ID cannot be empty")
            .is(nonEmptyString());

        String url = createDetailUrlFor(businessId);

        YelpBusinessDetails details = tryToGetDetailsAt(url);

        return details;

    }

    @Override
    public List<YelpBusiness> searchForBusinesses(YelpSearchRequest request) throws YelpException
    {
        AlchemyRequest.Step3 httpRequest = createHTTPRequestToSearch(this.apiKey, request);
        
        String url = baseURL + URLS.BUSINESS_SEARCH;
        
        YelpResponses.SearchResponse response;
        try
        {
            response = httpRequest.expecting(YelpResponses.SearchResponse.class)
                                  .at(url);
        }
        catch (AlchemyHttpException ex)
        {
            if (isBadAuth(ex))
            {
                throw new YelpAuthenticationException(ex);
            }
            
            if (isBadRequest(ex))
            {
                throw new YelpBadArgumentException(ex);
            }
            
            throw new YelpOperationFailedException(format("Failed to make search request at %s with [%s]", url, request), ex);
        }
        catch (Exception ex)
        {
            LOG.error("Failed to make search request at {}", url, ex);
            throw new YelpOperationFailedException("could not search Yelp at: " + url, ex);
        }

        if (response == null)
        {
            LOG.warn("Received null response from Yelp at {} for request {}", url, request);
            throw new YelpOperationFailedException("Received null response from yelp at:" + url);
        }
        
        List<YelpBusiness> results = Lists.nullToEmpty(response.businesses);
        
        LOG.info("Received {} results out of {} total for search: {}", results.size(), response.total, request);
        return results;
    }

    @Override
    public List<YelpReview> getReviewsForBusiness(String businessId) throws YelpException
    {
        checkToken(this.apiKey);
        
        String url = createUrlToGetReviewsFor(businessId);
        
        YelpResponses.ReviewsResponse response = tryToGetReviewsAt(url, this.apiKey);
        
        if (Objects.nonNull(response))
        {
            LOG.debug("Found {} total reviews for business {}", response.total, businessId);
            return Lists.nullToEmpty(response.reviews);
        }
        
        return Lists.emptyList();
    }
    
    private void checkToken(String apiKey) throws YelpAuthenticationException
    {
        checkThat(apiKey)
            .throwing(YelpAuthenticationException.class)
            .usingMessage("No apiKey available to make API call")
            .is(nonEmptyString());
    }
    
    private AlchemyRequest.Step3 createHTTPRequestToSearch(String token, YelpSearchRequest request)
    {
        AlchemyRequest.Step3 httpRequest = http.go()
            .get()
            .usingHeader(HeaderParameters.AUTHORIZATION, HeaderParameters.BEARER + " " + token);

        httpRequest = requestFilledWithParametersFrom(httpRequest, request);
        return httpRequest;
    }

    private String createDetailUrlFor(String businessId)
    {
        return format("%s%s/%s", baseURL, URLS.BUSINESSES, businessId);
    }
    
    private String createUrlToGetReviewsFor(String businessId)
    {
        return format("%s%s/%s%s", baseURL, URLS.BUSINESSES, businessId, URLS.REVIEWS);
    }
    
    private YelpBusinessDetails tryToGetDetailsAt(String url)
    {
        checkThat(url)
            .is(validURL());

        checkThat(this.apiKey)
            .throwing(YelpAuthenticationException.class)
            .usingMessage("missing Api Key")
            .is(nonEmptyString());
        
        try
        {
            return http.go()
                .get()
                .usingHeader(HeaderParameters.AUTHORIZATION, HeaderParameters.BEARER + " " + apiKey)
                .expecting(YelpBusinessDetails.class)
                .at(url);
        }
        catch (MalformedURLException ex)
        {
            LOG.error("Received strange malformed URL for {}", url, ex);
            throw new YelpBadArgumentException("Business ID led to invalid URL: " + url);
        }
        catch (AlchemyHttpException ex)
        {
            LOG.warn("Operation to make request at {} failed", url, ex);
            
            if (isBadAuth(ex))
            {
                throw new YelpAuthenticationException(ex);
            }
            else if (isBadRequest(ex))
            {
                throw new YelpBadArgumentException(ex);
            }
            else
            {
                throw new YelpOperationFailedException(ex);
            }
        }
        catch (Exception ex)
        {
            LOG.error("Failed to make request at {}", url, ex);
            throw new YelpOperationFailedException(ex);
        }
    }

    private YelpResponses.ReviewsResponse tryToGetReviewsAt(String url, String apiKey) throws YelpException
    {
        checkThat(url).is(validURL());
        checkThat(apiKey).is(nonEmptyString());

        try
        {
            return http
                .go()
                .get()
                .usingHeader(HeaderParameters.AUTHORIZATION, HeaderParameters.BEARER + " " + apiKey)
                .expecting(YelpResponses.ReviewsResponse.class)
                .at(url);
        }
        catch (AlchemyHttpException ex)
        {
            LOG.error("Failed to mkae Alchemy HTTP Call at [{]]", url, ex);

            if (isBadRequest(ex))
            {
                throw new YelpBadArgumentException("Bad Request", ex);
            }
            else if (isBadAuth(ex))
            {
                throw new YelpAuthenticationException("Invalid apiKey", ex);
            }
            else
            {
                throw new YelpOperationFailedException("Yelp call failed", ex);
            }
        }
        catch (Exception ex)
        {
            LOG.error("Failed to make HTTP Call at [{}]", url, ex);
            throw new YelpOperationFailedException("Yelp call failed to URL: " + url, ex);
        }
    }


    private AlchemyRequest.Step3 requestFilledWithParametersFrom(AlchemyRequest.Step3 httpRequest, YelpSearchRequest request)
    {
        
        if (request.hasAttributes())
        {
            httpRequest = httpRequest.usingQueryParam(SearchParameters.ATTRIBUTES, request.getAttributes());
        }
        
        if (request.hasCategories())
        {
            httpRequest = httpRequest.usingQueryParam(SearchParameters.CATEGORIES, request.getCategories());
        }
        
        if (request.hasIsOpenNow())
        {
            httpRequest = httpRequest.usingQueryParam(SearchParameters.OPEN_NOW, request.getOpenNow());
        }
        
        if (request.hasLatitude())
        {
            httpRequest = httpRequest.usingQueryParam(SearchParameters.LATITUDE, request.getLatitude());
        }
        
        if (request.hasLimit())
        {
            httpRequest = httpRequest.usingQueryParam(SearchParameters.LIMIT, request.getLimit());
        }
        
        if (request.hasLocale())
        {
            httpRequest = httpRequest.usingHeader(SearchParameters.LOCALE, request.getLocale());
        }
        
        if (request.hasLocation())
        {
            httpRequest = httpRequest.usingQueryParam(SearchParameters.LOCATION, request.getLocation());
        }
        
        if (request.hasLongitude())
        {
            httpRequest = httpRequest.usingQueryParam(SearchParameters.LONGITUDE, request.getLongitude());
        }
        
        if (request.hasOffset())
        {
            httpRequest = httpRequest.usingQueryParam(SearchParameters.OFFSET, request.getOffset());
        }
        
        if (request.hasOpenAt())
        {
            httpRequest = httpRequest.usingQueryParam(SearchParameters.OPEN_AT, request.getOpenAt());
        }
        
        if (request.hasPrices())
        {
            httpRequest = httpRequest.usingQueryParam(SearchParameters.PRICE, request.getPrices());
        }
        
        if (request.hasRadius())
        {
            httpRequest = httpRequest.usingQueryParam(SearchParameters.RADIUS, request.getRadius());
        }
        
        if (request.hasSearchTerm())
        {
            httpRequest = httpRequest.usingQueryParam(SearchParameters.SEARCH_TERM, request.getSearchTerm());
        }
        
        if (request.hasSortBy())
        {
            httpRequest = httpRequest.usingQueryParam(SearchParameters.SORT_BY, request.getSortBy());
        }
        
        return httpRequest;
    }
    
    private boolean isBadRequest(AlchemyHttpException ex)
    {
        int expectedStatus = 400;
        
        if (ex.hasResponse())
        {
            return ex.getResponse().statusCode() == expectedStatus;
        }
        
        return false;
    }
    
    private boolean isBadAuth(AlchemyHttpException ex)
    {
        int expectedStatus = 401;
        
        if (ex.hasResponse())
        {
            return ex.getResponse().statusCode() == expectedStatus;
        }
        
        return false;
    }
    
    @Override
    public int hashCode()
    {
        int hash = 5;
        hash = 29 * hash + Objects.hashCode(this.http);
        hash = 29 * hash + Objects.hashCode(this.apiKey);
        hash = 29 * hash + Objects.hashCode(this.baseURL);
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
        final YelpAPIImpl other = (YelpAPIImpl) obj;
        if (!Objects.equals(this.baseURL, other.baseURL))
        {
            return false;
        }
        if (!Objects.equals(this.http, other.http))
        {
            return false;
        }
        if (!Objects.equals(this.apiKey, other.apiKey))
        {
            return false;
        }
        return true;
    }
    
    @Override
    public String toString()
    {
        return "YelpAPIImpl{" + "http=" + http + ", apiKey=<redacted>, baseURL=" + baseURL + '}';
    }

    /**
     * URLs used to construct queries.
     */
    @NonInstantiable
    @Internal
    static class URLS
    {

        /**
         * https://api.yelp.com/v3/businesses
         */
        static final String BUSINESSES = "/businesses";

        /**
         * https://api.yelp.com/v3/businesses/search
         */
        static final String BUSINESS_SEARCH = "/businesses/search";

        /**
         * https://api.yelp.com/v3/businesses/{id}/reviews
         */
        static final String REVIEWS = "/reviews";
    }
    
    @NonInstantiable
    @Internal
    static class SearchParameters
    {

        static final String SEARCH_TERM = "term";
        static final String LOCATION = "location";
        static final String LATITUDE = "latitude";
        static final String LONGITUDE = "longitude";
        static final String RADIUS = "radius";
        static final String CATEGORIES = "categories";
        static final String LOCALE = "locale";
        static final String LIMIT = "limit";
        static final String OFFSET = "offset";
        static final String SORT_BY = "sort_by";
        static final String PRICE = "price";
        static final String OPEN_NOW = "open_now";
        static final String OPEN_AT = "open_at";
        static final String ATTRIBUTES = "attributes";
    }
    
    @NonInstantiable
    @Internal
    static class HeaderParameters
    {

        static final String AUTHORIZATION = "Authorization";
        static final String BEARER = "Bearer";
    }

}
