/*
 * Copyright 2004-2008 the original author or authors.
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
package org.springframework.js.ajax.tiles2;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.tiles.Attribute;
import org.apache.tiles.AttributeContext;
import org.apache.tiles.Definition;
import org.apache.tiles.TilesContainer;
import org.apache.tiles.access.TilesAccess;
import org.apache.tiles.context.TilesRequestContext;
import org.apache.tiles.impl.BasicTilesContainer;
import org.springbyexample.web.servlet.view.tiles2.DynamicTilesViewProcessor;
import org.springframework.js.ajax.AjaxHandler;
import org.springframework.js.ajax.SpringJavascriptAjaxHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.support.JstlUtils;
import org.springframework.web.servlet.support.RequestContext;
import org.springframework.web.servlet.view.tiles2.TilesView;

/**
 * Tiles view implementation that is able to handle partial rendering for Spring Javascript Ajax requests.
 * 
 * <p>
 * This implementation uses the {@link SpringJavascriptAjaxHandler} by default to determine whether the current request
 * is an Ajax request. On an Ajax request, a "fragments" parameter will be extracted from the request in order to
 * determine which attributes to render from the current tiles view.
 * </p>
 * 
 * @author Jeremy Grelle
 * @author David Winterfeldt
 */
public class AjaxTilesView extends TilesView {

    private static final String FRAGMENTS_PARAM = "fragments";

    private AjaxHandler ajaxHandler = new SpringJavascriptAjaxHandler();
    private DynamicTilesViewProcessor dynamicTilesViewProcessor = new DynamicTilesViewProcessor();

    /**
     * Gets <code>AjaxHandler</code>.
     */
    public AjaxHandler getAjaxHandler() {
        return ajaxHandler;
    }

    /**
     * Sets <code>AjaxHandler</code>.
     */
    public void setAjaxHandler(AjaxHandler ajaxHandler) {
        this.ajaxHandler = ajaxHandler;
    }

    /**
     * Renders output using Tiles and also can handle rendering AJAX fragments.
     */
    protected void renderMergedOutputModel(Map model, HttpServletRequest request, HttpServletResponse response)
            throws Exception {

        String beanName = getBeanName();
        String url = getUrl();

        ServletContext servletContext = getServletContext();
        BasicTilesContainer container = (BasicTilesContainer) TilesAccess.getContainer(servletContext);

        if (container == null) {
            throw new ServletException("Tiles container is not initialized. " + 
                                       "Have you added a TilesConfigurer to your web application context?");
        }

        if (ajaxHandler.isAjaxRequest(request, response)) {
            // change URL used to lookup tiles template to correct definition
            String definitionName = dynamicTilesViewProcessor.startDynamicDefinition(beanName, url, request, response, container);
            
            String[] attrNames = getRenderFragments(model, request, response);
            if (attrNames.length == 0) {
                logger.warn("An Ajax request was detected, but no fragments were specified to be re-rendered.  "
                        + "Falling back to full page render.");
                super.renderMergedOutputModel(model, request, response);
            }

            exposeModelAsRequestAttributes(model, request);
            JstlUtils.exposeLocalizationContext(new RequestContext(request, servletContext));

            TilesRequestContext tilesRequestContext = container.getContextFactory().createRequestContext(
                    container.getApplicationContext(), new Object[] { request, response });
            Definition compositeDefinition = container.getDefinitionsFactory().getDefinition(definitionName, tilesRequestContext);
            Map flattenedAttributeMap = new HashMap();
            flattenAttributeMap(container, tilesRequestContext, flattenedAttributeMap, compositeDefinition, request,
                    response);

            // initialize the session before rendering any fragments. Otherwise views that require the session which has
            // not otherwise been initialized will fail to render
            request.getSession();
            response.flushBuffer();
            for (int i = 0; i < attrNames.length; i++) {
                Attribute attributeToRender = (Attribute) flattenedAttributeMap.get(attrNames[i]);

                if (attributeToRender == null) {
                    throw new ServletException("No tiles attribute with a name of '" + attrNames[i]
                            + "' could be found for the current view: " + this);
                } else {
                    container.render(attributeToRender, response.getWriter(), new Object[] { request, response });
                }
            }
            
            dynamicTilesViewProcessor.endDynamicDefinition(definitionName, beanName, request, response, container);
        } else {
            exposeModelAsRequestAttributes(model, request);

            dynamicTilesViewProcessor.renderMergedOutputModel(beanName, url, 
                                                              servletContext, request, response, container);
        }
    }

    protected String[] getRenderFragments(Map model, HttpServletRequest request, HttpServletResponse response) {
        String attrName = request.getParameter(FRAGMENTS_PARAM);
        String[] renderFragments = StringUtils.commaDelimitedListToStringArray(attrName);
        return StringUtils.trimArrayElements(renderFragments);
    }

    /**
     * Get flattened attribute map.  Used for processing what tiles attributes/sections are available.
     */
    protected void flattenAttributeMap(BasicTilesContainer container, TilesRequestContext requestContext,
            Map resultMap, Definition compositeDefinition, HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        if (compositeDefinition.getAttributes() != null && compositeDefinition.getAttributes().size() > 0) {
            Iterator i = compositeDefinition.getAttributes().keySet().iterator();
            while (i.hasNext()) {
                Object key = i.next();
                Attribute attr = (Attribute) compositeDefinition.getAttributes().get(key);
                Definition nestedDefinition = container.getDefinitionsFactory().getDefinition(
                        attr.getValue().toString(), requestContext);
                resultMap.put(key, attr);
                if (nestedDefinition != null && nestedDefinition != compositeDefinition) {
                    flattenAttributeMap(container, requestContext, resultMap, nestedDefinition, request, response);
                }
            }
        }

        // Process dynamic attributes
        AttributeContext attributeContext = container.getAttributeContext(new Object[] { request, response });

        for (Iterator i = attributeContext.getAttributeNames(); i.hasNext();) {
            String key = (String) i.next();
            Attribute attr = attributeContext.getAttribute(key);
            resultMap.put(key, attr);
        }
    }
}