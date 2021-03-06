/**
 *  Copyright 2019 Martynas Jusevičius <martynas@atomgraph.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.atomgraph.linkeddatahub.server.filter.request.auth;

import com.atomgraph.core.MediaTypes;
import com.atomgraph.core.io.ModelProvider;
import com.atomgraph.core.vocabulary.SD;
import com.atomgraph.linkeddatahub.client.DataManager;
import com.atomgraph.linkeddatahub.client.NoCertClient;
import com.atomgraph.linkeddatahub.exception.auth.AuthorizationException;
import com.atomgraph.linkeddatahub.exception.auth.InvalidWebIDPublicKeyException;
import com.atomgraph.linkeddatahub.exception.auth.InvalidWebIDURIException;
import com.atomgraph.linkeddatahub.exception.auth.WebIDCertificateException;
import com.atomgraph.linkeddatahub.exception.auth.WebIDLoadingException;
import com.atomgraph.linkeddatahub.model.Agent;
import com.atomgraph.linkeddatahub.apps.model.EndUserApplication;
import com.atomgraph.linkeddatahub.client.filter.CacheControlFilter;
import com.atomgraph.linkeddatahub.exception.auth.WebIDDelegationException;
import com.atomgraph.linkeddatahub.server.provider.ApplicationProvider;
import com.atomgraph.linkeddatahub.vocabulary.ACL;
import com.atomgraph.linkeddatahub.vocabulary.LACL;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.spi.container.ContainerRequest;
import com.sun.jersey.spi.container.ContainerRequestFilter;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.ext.Providers;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolutionMap;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spinrdf.vocabulary.SPIN;

/**
 * WebID authentication request filter.
 * Queries for matching authorizations using SPARQL query against administrative service.
 * 
 * @author Martynas Jusevičius {@literal <martynas@atomgraph.com>}
 */
public class WebIDFilter implements ContainerRequestFilter // extends AuthFilter
{
    
    private static final Logger log = LoggerFactory.getLogger(WebIDFilter.class);

    public static final int SNA_URI_NAME = 6;
    public static final String ON_BEHALF_OF = "On-Behalf-Of";
    
    private final MediaTypes mediaTypes = new MediaTypes();
    private final javax.ws.rs.core.MediaType[] acceptedTypes;
    
    @Context HttpServletRequest httpServletRequest;
    @Context Providers providers;
    @Context Application system;

    private ParameterizedSparqlString authQuery, ownerAuthQuery, webIDQuery;

    public WebIDFilter()
    {
        List<javax.ws.rs.core.MediaType> acceptedTypeList = new ArrayList();
        acceptedTypeList.addAll(mediaTypes.getReadable(Model.class));
        acceptedTypes = acceptedTypeList.toArray(new javax.ws.rs.core.MediaType[acceptedTypeList.size()]); 
    }
    
    @PostConstruct
    public void init()
    {
        authQuery = new ParameterizedSparqlString(getSystem().getAuthQuery().toString());
        ownerAuthQuery = new ParameterizedSparqlString(getSystem().getOwnerAuthQuery().toString());
        webIDQuery = new ParameterizedSparqlString(getSystem().getWebIDQuery().toString());
    }
    
    public String getScheme()
    {
        return SecurityContext.CLIENT_CERT_AUTH;
    }

    @Override
    public ContainerRequest filter(ContainerRequest request)
    {
        if (request == null) throw new IllegalArgumentException("ContainerRequest cannot be null");
        if (log.isDebugEnabled()) log.debug("Authenticating request URI: {}", request.getRequestUri());
        
        Resource accessMode = null;
        if (request.getMethod().equalsIgnoreCase(HttpMethod.GET) || request.getMethod().equalsIgnoreCase(HttpMethod.HEAD) ||
                request.getMethod().equalsIgnoreCase("com.sun.jersey.MATCH_RESOURCE")) accessMode = ACL.Read;
        if (request.getMethod().equalsIgnoreCase(HttpMethod.POST)) accessMode = ACL.Append;
        if (request.getMethod().equalsIgnoreCase(HttpMethod.PUT)) accessMode = ACL.Write;
        if (request.getMethod().equalsIgnoreCase(HttpMethod.DELETE)) accessMode = ACL.Write;
        if (request.getMethod().equalsIgnoreCase("PATCH")) accessMode = ACL.Write;
        if (log.isDebugEnabled()) log.debug("Request method: {} ACL access mode: {}", request.getMethod(), accessMode);
        if (accessMode == null)
        {
            if (log.isWarnEnabled()) log.warn("Skipping authentication/authorization, request method not recognized: {}", request.getMethod());
            return request;
        }
        
        com.atomgraph.linkeddatahub.apps.model.Application app = getApplicationProvider().getApplication(getHttpServletRequest());

        // logout not really possible with HTTP certificates
        //if (isLogoutForced(request, getScheme())) logout(app, request);
        
        X509Certificate[] certs = (X509Certificate[])getHttpServletRequest().getAttribute("javax.servlet.request.X509Certificate");
        if (certs == null) return request;

        if (!(request.getSecurityContext().getUserPrincipal() instanceof Agent))
            try
            {
                X509Certificate webIDCert = null;
                for (X509Certificate cert : certs)
                    if (getWebIDURI(cert) != null) webIDCert = cert;
                
                if (log.isTraceEnabled()) log.trace("Client WebID certificate: {}", webIDCert);
                if (webIDCert == null) return request;
                
                webIDCert.checkValidity(); // check if certificate is expired or not yet valid
                RSAPublicKey publicKey = (RSAPublicKey)webIDCert.getPublicKey();
                URI webID = getWebIDURI(webIDCert);
                if (log.isTraceEnabled()) log.trace("Client WebID: {}", webID);

                try
                {
                    Resource agent = authenticate(loadWebID(webID), webID, publicKey);
                    if (agent == null)
                    {
                        if (log.isErrorEnabled()) log.error("Client certificate public key did not match WebID public key: {}", webID);
                        throw new InvalidWebIDPublicKeyException(publicKey, webID.toString());
                    }
                    getSystem().getWebIDModelCache().put(webID, agent.getModel()); // now it's safe to cache the WebID Model

                    String onBehalfOf = request.getHeaderValue(ON_BEHALF_OF);
                    if (onBehalfOf != null)
                    {
                        URI principalWebID = new URI(onBehalfOf);
                        Model principalWebIDModel = loadWebID(principalWebID);
                        Resource principal = principalWebIDModel.createResource(onBehalfOf);
                        // if we verify that the current agent is a secretary of the principal, that principal becomes current agent. Else throw error
                        if (agent.equals(principal) || agent.getModel().contains(agent, ACL.delegates, principal)) agent = principal;
                        else throw new WebIDDelegationException(agent, principal);
                    }
                    
                    // imitate type inference, otherwise we'll get Jena's polymorphism exception
                    request.setSecurityContext(new AgentContext(agent.addProperty(RDF.type, LACL.Agent).as(Agent.class), getScheme()));

                    if (app != null)
                    {
                        Resource authorization = authorize(app, request, agent, accessMode);
                        ((AgentContext)request.getSecurityContext()).getAgent().getModel().add(authorization.getModel());
                    }

                    return request;
                }
                catch (ClientHandlerException ex)
                {
                    if (log.isErrorEnabled()) log.error("Error loading RDF data from WebID URI: {}", webID, ex);
                    return request; // default to unauthenticated access
                }
            }
            catch (CertificateException ex)
            {
                if (log.isErrorEnabled()) log.error("WebID certificate error (could not parse, expired or not yet valid)", ex);
                throw new WebIDCertificateException(ex);
            }
            catch (URISyntaxException ex)
            {
                if (log.isErrorEnabled()) log.error("Could not parse WebID URI: {}", ex.getInput(), ex);
                throw new InvalidWebIDURIException(ex.getInput());
            }
        else
        {
            if (app != null)
            {
                Resource agent = ((Agent)(request.getSecurityContext().getUserPrincipal()));
                authorize(app, request, agent, accessMode);
            }
            
            return request;
        }
    }
    
    public static URI getWebIDURI(X509Certificate cert) throws URISyntaxException, CertificateParsingException
    {
        if (cert.getSubjectAlternativeNames() != null)
        {
            List<?>[] snas = cert.getSubjectAlternativeNames().toArray(new List<?>[0]);
            if (snas.length > 0 && cert.getPublicKey() instanceof RSAPublicKey)
                for (List<?> sna : snas)
                {
                    Object type = sna.get(0);
                    if (Integer.valueOf(type.toString()).equals(SNA_URI_NAME))
                    {
                        Object value = sna.get(1);
                        return new URI(value.toString());
                    }
                }
        }
        
        return null;
    }
    
    public Resource authenticate(Model webIDModel, URI webID, RSAPublicKey publicKey)
    {
        ParameterizedSparqlString pss = getWebIDQuery().copy();
        // pss.setIri("webid", webID.toString()); // do not set ?webid as we will be SELECTing it
        pss.setLiteral("exp", ResourceFactory.createTypedLiteral(publicKey.getPublicExponent()));
        pss.setLiteral("mod", ResourceFactory.createTypedLiteral(publicKey.getModulus().toString(16), XSDDatatype.XSDhexBinary));

        try (QueryExecution qex = QueryExecutionFactory.create(pss.asQuery(), webIDModel))
        {
            ResultSet resultSet = qex.execSelect();
            if (resultSet.hasNext())
            {
                Resource agent = resultSet.next().getResource("webid");
                if (agent != null && agent.isURIResource() && agent.getURI().equals(webID.toString()))
                    return agent; // ?webid with matching key values is our WebID agent
            }
        }

        return null;
    }
  
    public Model loadWebID(URI webID)
    {
        if (getSystem().getWebIDModelCache().containsKey(webID)) return getSystem().getWebIDModelCache().get(webID);
        
        Model model = loadWebIDFromURI(webID);
        
        return model;
    }
    
    public Model loadWebIDFromURI(URI webID)
    {
        ClientResponse cr = null;
        
        try
        {
            // remove fragment identifier to get document URI
            URI webIDDoc = new URI(webID.getScheme(), webID.getSchemeSpecificPart(), null).normalize();
            cr = getNoCertClient().resource(webIDDoc).
                    accept(getAcceptableMediaTypes()).
                    get(ClientResponse.class);
            
            if (!cr.getStatusInfo().getFamily().equals(Response.Status.Family.SUCCESSFUL))
            {
                if (log.isErrorEnabled()) log.error("Could not load WebID: {}", webID.toString());
                throw new WebIDLoadingException(cr);
            }
            cr.getHeaders().putSingle(ModelProvider.REQUEST_URI_HEADER, webIDDoc.toString()); // provide a base URI hint to ModelProvider
            
            return cr.getEntity(Model.class);
        }
        catch (URISyntaxException ex)
        {
            // can't happen
        }
        finally
        {
            if (cr != null) cr.close();
        }
        
        return null;
    }
    
    public QuerySolutionMap getQuerySolutionMap(com.atomgraph.linkeddatahub.apps.model.Application app, Resource absolutePath, Resource agent, Resource accessMode)
    {
        QuerySolutionMap qsm = new QuerySolutionMap();
        if (app.canAs(EndUserApplication.class)) qsm.add(SD.endpoint.getLocalName(), app.getService().getSPARQLEndpoint()); // needed for federation with the end-user endpoint        
        qsm.add("AuthenticatedAgentClass", ACL.AuthenticatedAgent); // enable AuthenticatedAgent UNION branch
        qsm.add("agent", agent);
        qsm.add(SPIN.THIS_VAR_NAME, absolutePath);
        //qsm.add(LDT.base.getLocalName(), base);
        qsm.add("Mode", accessMode);
        return qsm;
    }
    
    public Resource authorize(com.atomgraph.linkeddatahub.apps.model.Application app, ContainerRequest request, Resource agent, Resource accessMode)
    {
        return authorize(app, request, accessMode,
                getQuerySolutionMap(app, ResourceFactory.createResource(request.getAbsolutePath().toString()), agent, accessMode));
    }
        
    public Resource authorize(com.atomgraph.linkeddatahub.apps.model.Application app, ContainerRequest request, Resource accessMode, QuerySolutionMap qsm)
    {
        final ParameterizedSparqlString pss;
        final com.atomgraph.linkeddatahub.model.Service adminService; // always run auth queries on admin Service
        if (app.canAs(EndUserApplication.class))
        {
            pss = getAuthQuery().copy();
            adminService = app.as(EndUserApplication.class).getAdminApplication().getService();
        }
        else
        {
            pss = getOwnerAuthQuery().copy();
            adminService = app.getService();
        }

        pss.setParams(qsm); // apply variable bindings to the query string
        Model authModel = loadAuth(pss.asQuery(), adminService);
        
        // type check will not work on LACL subclasses without InfModel
        Resource authorization = getResourceByPropertyValue(authModel, ACL.mode, null);
        if (authorization == null) authorization = getResourceByPropertyValue(authModel, ResourceFactory.createProperty(LACL.NS + "accessProperty"), null); // creator access
        
        if (authorization == null)
        {
            if (log.isTraceEnabled()) log.trace("Access not authorized for request URI: {} and access mode: {}", request.getAbsolutePath(), accessMode);
            throw new AuthorizationException("Access not authorized for request URI", request.getAbsolutePath(), accessMode);
        }
            
        return authorization;
    }
    
    // TO-DO: extend from AuthFilter
    /**
     * Loads authorization graph from the admin service.
     * 
     * @param query auth query string
     * @param service SPARQL service
     * @return authorization graph (can be empty)
     * @see com.atomgraph.linkeddatahub.vocabulary.APLC#authQuery
     */
    protected Model loadAuth(Query query, com.atomgraph.linkeddatahub.model.Service service)
    {
        if (query == null) throw new IllegalArgumentException("Query cannot be null");
        if (service == null) throw new IllegalArgumentException("Service cannot be null");

        return service.getSPARQLClient().
                addFilter(new CacheControlFilter(CacheControl.valueOf("no-cache"))). // add Cache-Control: no-cache to request
                query(query, Model.class, null).
                getEntity(Model.class);
    }
    
    protected Resource getResourceByPropertyValue(Model model, Property property, RDFNode value)
    {
        if (model == null) throw new IllegalArgumentException("Model cannot be null");
        if (property == null) throw new IllegalArgumentException("Property cannot be null");
        
        ResIterator it = model.listSubjectsWithProperty(property, value);
        
        try
        {
            if (it.hasNext()) return it.next();
        }
        finally
        {
            it.close();
        }
        
        return null;
    }
    
    public boolean isApplied(com.atomgraph.linkeddatahub.apps.model.Application app, String realm, ContainerRequest request)
    {
        return true;
    }
    
    public HttpServletRequest getHttpServletRequest()
    {
        return httpServletRequest;
    }
    
    public ApplicationProvider getApplicationProvider()
    {
        return ((ApplicationProvider)getProviders().getContextResolver(com.atomgraph.linkeddatahub.apps.model.Application.class, null));
    }
    
    public Providers getProviders()
    {
        return providers;
    }
    
    public com.atomgraph.linkeddatahub.Application getSystem()
    {
        return (com.atomgraph.linkeddatahub.Application)system;
    }
    
    public ParameterizedSparqlString getAuthQuery()
    {
        return authQuery;
    }

    public ParameterizedSparqlString getOwnerAuthQuery()
    {
        return ownerAuthQuery;
    }
    
    public ParameterizedSparqlString getWebIDQuery()
    {
        return webIDQuery;
    }
    
    public DataManager getDataManager()
    {
        return getProviders().getContextResolver(DataManager.class, null).getContext(DataManager.class);
    }
    
    public NoCertClient getNoCertClient()
    {
        return getProviders().getContextResolver(NoCertClient.class, null).getContext(NoCertClient.class);
    }
    
    public javax.ws.rs.core.MediaType[] getAcceptableMediaTypes()
    {
        return acceptedTypes;
    }
    
}