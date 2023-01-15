/*
 * Copyright (c) 2022, 2023 Contributors to the Eclipse Foundation
 * Copyright (c) 2008, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package com.sun.enterprise.glassfish.web;

import com.sun.enterprise.config.serverbeans.Config;
import com.sun.enterprise.config.serverbeans.HttpService;
import com.sun.enterprise.config.serverbeans.VirtualServer;
import com.sun.enterprise.deploy.shared.AbstractArchiveHandler;
import com.sun.enterprise.security.perms.PermsArchiveDelegate;
import com.sun.enterprise.security.perms.SMGlobalPolicyUtil;
import com.sun.enterprise.util.StringUtils;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.naming.resources.WebDirContext;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.api.deployment.DeployCommandParameters;
import org.glassfish.api.deployment.DeploymentContext;
import org.glassfish.api.deployment.archive.ArchiveDetector;
import org.glassfish.api.deployment.archive.ReadableArchive;
import org.glassfish.deployment.common.DeploymentProperties;
import org.glassfish.loader.util.ASClassLoaderUtil;
import org.glassfish.web.WarType;
import org.glassfish.web.loader.LogFacade;
import org.glassfish.web.loader.WebappClassLoader;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.config.types.Property;

import static javax.xml.stream.XMLStreamConstants.END_DOCUMENT;
import static javax.xml.stream.XMLStreamConstants.END_ELEMENT;
import static javax.xml.stream.XMLStreamConstants.START_ELEMENT;

/**
 * Implementation of the ArchiveHandler for war files.
 *
 * @author Jerome Dochez, Sanjeeb Sahoo, Shing Wai Chan
 */
@Service(name = WarType.ARCHIVE_TYPE)
public class WarHandler extends AbstractArchiveHandler {

    private static final String GLASSFISH_WEB_XML = "WEB-INF/glassfish-web.xml";
    private static final String SUN_WEB_XML = "WEB-INF/sun-web.xml";
    private static final String WEBLOGIC_XML = "WEB-INF/weblogic.xml";
    private static final String WAR_CONTEXT_XML = "META-INF/context.xml";
    private static final String DEFAULT_CONTEXT_XML = "config/context.xml";

    private static final Logger LOG = LogFacade.getLogger();
    private static final ResourceBundle I18N = LOG.getResourceBundle();

    // the following two system properties need to be in sync with DOLUtils
    private static final boolean gfDDOverWLSDD = Boolean.valueOf(System.getProperty("gfdd.over.wlsdd"));
    private static final boolean ignoreWLSDD = Boolean.valueOf(System.getProperty("ignore.wlsdd"));

    @Inject
    @Named(WarType.ARCHIVE_TYPE)
    private ArchiveDetector detector;

    @Inject
    @Named(ServerEnvironment.DEFAULT_INSTANCE_NAME)
    private Config serverConfig;

    @Inject
    private ServerEnvironment serverEnvironment;

    @Override
    public String getArchiveType() {
        return WarType.ARCHIVE_TYPE;
    }


    @Override
    public String getVersionIdentifier(ReadableArchive archive) {
        try {
            WebXmlParser webXmlParser = getWebXmlParser(archive);
            return webXmlParser.getVersionIdentifier();
        } catch (XMLStreamException e) {
            LOG.log(Level.SEVERE, e.getMessage());
        } catch (IOException e) {
            LOG.log(Level.SEVERE, e.getMessage());
        }
        return null;
    }

    @Override
    public boolean handles(ReadableArchive archive) throws IOException {
        return detector.handles(archive);
    }

    @Override
    public ClassLoader getClassLoader(final ClassLoader parent, DeploymentContext context) {
        PrivilegedAction<WebappClassLoader> action = () -> new WebappClassLoader(parent);
        WebappClassLoader cloader = AccessController.doPrivileged(action);
        try {
            WebDirContext webDirContext = new WebDirContext();
            File base = new File(context.getSource().getURI());
            webDirContext.setDocBase(base.getAbsolutePath());

            cloader.setResources(webDirContext);
            cloader.addRepository("WEB-INF/classes/", new File(base, "WEB-INF/classes/"));
            if (context.getScratchDir("ejb") != null) {
                cloader.addRepository(context.getScratchDir("ejb").toURI().toURL().toString().concat("/"));
            }
            if (context.getScratchDir("jsp") != null) {
                cloader.setWorkDir(context.getScratchDir("jsp"));
            }

             // add libraries referenced from manifest
            for (URL url : getManifestLibraries(context)) {
                cloader.addRepository(url.toString());
            }

            WebXmlParser webXmlParser = getWebXmlParser(context.getSource());
            configureLoaderAttributes(cloader, webXmlParser, base);
            configureLoaderProperties(cloader, webXmlParser, base);
            configureContextXmlAttribute(cloader, base, context);
            try {
                final DeploymentContext dc = context;
                final ClassLoader cl = cloader;
                AccessController.doPrivileged(
                    new PermsArchiveDelegate.SetPermissionsAction(SMGlobalPolicyUtil.CommponentType.war, dc, cl));
            } catch (PrivilegedActionException e) {
                throw new SecurityException(e.getException());
            }

        } catch(XMLStreamException xse) {
            LOG.log(Level.SEVERE, xse.getMessage(), xse);
        } catch(IOException ioe) {
            LOG.log(Level.SEVERE, ioe.getMessage(), ioe);
        }
        cloader.start();
        return cloader;
    }


    protected WebXmlParser getWebXmlParser(ReadableArchive archive) throws XMLStreamException, IOException {
        final boolean hasWSLDD = archive.exists(WEBLOGIC_XML);
        final File runtimeAltDDFile = archive.getArchiveMetaData(DeploymentProperties.RUNTIME_ALT_DD, File.class);
        if (runtimeAltDDFile != null
            && "glassfish-web.xml".equals(runtimeAltDDFile.getPath())
            && runtimeAltDDFile.isFile()) {
            return new GlassFishWebXmlParser(archive);
        } else if (!gfDDOverWLSDD && !ignoreWLSDD && hasWSLDD) {
            return new WeblogicXmlParser(archive);
        } else if (archive.exists(GLASSFISH_WEB_XML)) {
            return new GlassFishWebXmlParser(archive);
        } else if (archive.exists(SUN_WEB_XML)) {
            return new SunWebXmlParser(archive);
        } else if (gfDDOverWLSDD && !ignoreWLSDD && hasWSLDD) {
            return new WeblogicXmlParser(archive);
        } else {
            // default
            if (gfDDOverWLSDD || ignoreWLSDD) {
                return new GlassFishWebXmlParser(archive);
            }
            return new WeblogicXmlParser(archive);
        }
    }


    protected void configureLoaderAttributes(WebappClassLoader cloader, WebXmlParser webXmlParser, File base) {
        final boolean delegate = webXmlParser.isDelegate();
        cloader.setDelegate(delegate);
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("WebModule[" + base + "]: Setting delegate to " + delegate);
        }

        final String extraClassPath = webXmlParser.getExtraClassPath();
        if (extraClassPath == null) {
            return;
        }
        // Parse the extra classpath into its ':' and ';' separated
        // components. Ignore ':' as a separator if it is preceded by
        // '\'
        String[] pathElements = extraClassPath.split(";|((?<!\\\\):)");
        for (String path : pathElements) {
            path = path.replace("\\:", ":");
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("WarHandler[" + base + "]: Adding " + path + " to the classpath");
            }

            try {
                new URL(path);
                cloader.addRepository(path);
            } catch (MalformedURLException mue1) {
                // Not a URL, interpret as file
                File file = new File(path);
                if (!file.isAbsolute()) {
                    // Resolve relative extra class path to the context's docroot
                    file = new File(base.getPath(), path);
                }
                try {
                    URL url = file.toURI().toURL();
                    cloader.addRepository(url.toString());
                } catch (MalformedURLException mue2) {
                    String msg = I18N.getString(LogFacade.CLASSPATH_ERROR);
                    Object[] params = { path };
                    msg = MessageFormat.format(msg, params);
                    LOG.log(Level.SEVERE, msg, mue2);
                }
            }
        }
    }


    protected void configureLoaderProperties(WebappClassLoader cloader, WebXmlParser webXmlParser, File base) {
        cloader.setUseMyFaces(webXmlParser.isUseBundledJSF());
        final File libDir = new File(base, "WEB-INF/lib");
        if (!libDir.exists()) {
            return;
        }
        final int baseFileLen = base.getPath().length();
        final boolean ignoreHiddenJarFiles = webXmlParser.isIgnoreHiddenJarFiles();
        final FileFilter fileFilter = pathname -> {
            final String fileName = pathname.getName();
            return ((fileName.endsWith(".jar") || fileName.endsWith(".zip"))
                && (!ignoreHiddenJarFiles || !fileName.startsWith(".")));
        };
        final File[] files = libDir.listFiles(fileFilter);
        if (files == null) {
            return;
        }
        for (final File file : files) {
            try {
                if (file.isDirectory()) {
                    // support exploded jar file
                    cloader.addRepository("WEB-INF/lib/" + file.getName() + "/", file);
                } else {
                    cloader.addJar(file.getPath().substring(baseFileLen), file);
                }
            } catch (final Exception e) {
                LOG.log(Level.FINEST, "Could not add file " + file, e);
            }
        }
    }


    protected void configureContextXmlAttribute(WebappClassLoader cloader, File base, DeploymentContext dc)
        throws XMLStreamException, IOException {
        boolean consistent = true;
        Boolean value = null;
        File warContextXml = new File(base.getAbsolutePath(), WAR_CONTEXT_XML);
        if (warContextXml.exists()) {
            ContextXmlParser parser = new ContextXmlParser(warContextXml);
            value = parser.getClearReferencesStatic();
        }

        if (value == null) {
            Boolean domainCRS = null;
            File defaultContextXml = new File(serverEnvironment.getInstanceRoot(), DEFAULT_CONTEXT_XML);
            if (defaultContextXml.exists()) {
                ContextXmlParser parser = new ContextXmlParser(defaultContextXml);
                domainCRS = parser.getClearReferencesStatic();
            }

            List<Boolean> csrs = new ArrayList<>();
            HttpService httpService = serverConfig.getHttpService();
            DeployCommandParameters params = dc.getCommandParameters(DeployCommandParameters.class);
            String vsIDs = params.virtualservers;
            List<String> vsList = StringUtils.parseStringList(vsIDs, " ,");
            if (httpService != null && vsList != null && !vsList.isEmpty()) {
                for (VirtualServer vsBean : httpService.getVirtualServer()) {
                    if (vsList.contains(vsBean.getId())) {
                        Boolean csr = null;
                        Property prop = vsBean.getProperty("contextXmlDefault");
                        if (prop != null) {
                            File contextXml = new File(serverEnvironment.getInstanceRoot(), prop.getValue());
                            if (contextXml.exists()) { // vs context.xml
                                ContextXmlParser parser = new ContextXmlParser(contextXml);
                                csr = parser.getClearReferencesStatic();
                            }
                        }

                        if (csr == null) {
                            csr = domainCRS;
                        }
                        csrs.add(csr);
                    }
                }

                // check that it is consistent
                for (Boolean b : csrs) {
                    if (b != null) {
                        if (value != null && !b.equals(value)) {
                            consistent = false;
                            break;
                        }
                        value = b;
                    }
                }

            }
        }

        if (consistent) {
            if (value != null) {
                cloader.setClearReferencesStatic(value);
            }
        } else if (LOG.isLoggable(Level.WARNING)) {
            LOG.log(Level.WARNING, LogFacade.INCONSISTENT_CLEAR_REFERENCE_STATIC);
        }
    }


    /**
     * Returns the classpath URIs for this archive.
     *
     * @param archive file
     * @return classpath URIs for this archive
     */
    @Override
    public List<URI> getClassPathURIs(ReadableArchive archive) {
        List<URI> uris = super.getClassPathURIs(archive);
        try {
            File archiveFile = new File(archive.getURI());
            if (archiveFile.exists() && archiveFile.isDirectory()) {
                uris.add(new URI(archive.getURI().toString() + "WEB-INF/classes/"));
                File webInf = new File(archiveFile, "WEB-INF");
                File webInfLib = new File(webInf, "lib");
                if (webInfLib.exists()) {
                    uris.addAll(ASClassLoaderUtil.getLibDirectoryJarURIs(webInfLib));
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
        return uris;
    }

    // ---- inner class ----
    protected abstract class BaseXmlParser {

        protected XMLStreamReader parser;

        /**
         * This method will parse the input stream and set the XMLStreamReader
         * object for latter use.
         *
         * @param input InputStream
         * @exception XMLStreamException;
         */
        protected abstract void read(InputStream input) throws XMLStreamException;


        protected void init(InputStream input) throws XMLStreamException {
            try {
                read(input);
            } finally {
                if (parser != null) {
                    try {
                        parser.close();
                    } catch (Exception ex) {
                        // ignore
                    }
                }
            }
        }


        protected void skipRoot(String name) throws XMLStreamException {
            while (true) {
                int event = parser.next();
                if (event == START_ELEMENT) {
                    String localName = parser.getLocalName();
                    if (!name.equals(localName)) {
                        String msg = I18N.getString(LogFacade.UNEXPECTED_XML_ELEMENT);
                        msg = MessageFormat.format(msg, new Object[] {name, localName});
                        throw new XMLStreamException(msg);
                    }
                    return;
                }
            }
        }


        protected void skipSubTree(String name) throws XMLStreamException {
            while (true) {
                int event = parser.next();
                if (event == END_DOCUMENT) {
                    throw new XMLStreamException(I18N.getString(LogFacade.UNEXPECTED_END_DOCUMENT));
                } else if (event == END_ELEMENT && name.equals(parser.getLocalName())) {
                    return;
                }
            }
        }

    }

    protected abstract class WebXmlParser extends BaseXmlParser {

        protected boolean delegate = WebappClassLoader.DELEGATE_DEFAULT;
        protected boolean ignoreHiddenJarFiles = false;
        protected boolean useBundledJSF = false;
        protected String extraClassPath = null;
        protected String versionIdentifier = null;

        WebXmlParser(ReadableArchive archive) throws XMLStreamException, IOException {
            if (archive.exists(getXmlFileName())) {
                try (InputStream is = archive.getEntry(getXmlFileName())) {
                    init(is);
                } catch (Throwable t) {
                    String msg = MessageFormat.format("Error in parsing {0} for archive [{1}]: {2}", getXmlFileName(),
                        archive.getURI(), t.getMessage());
                    throw new RuntimeException(msg);
                }
            }
        }


        protected abstract String getXmlFileName();


        boolean isDelegate() {
            return delegate;
        }


        boolean isIgnoreHiddenJarFiles() {
            return ignoreHiddenJarFiles;
        }


        String getExtraClassPath() {
            return extraClassPath;
        }


        boolean isUseBundledJSF() {
            return useBundledJSF;
        }


        String getVersionIdentifier() {
            return versionIdentifier;
        }
    }

    protected class SunWebXmlParser extends WebXmlParser {
        // XXX need to compute the default delegate depending on the version of dtd
        /*
         * The DOL will *always* return a value: If 'delegate' has not been
         * configured in sun-web.xml, its default value will be returned,
         * which is FALSE in the case of sun-web-app_2_2-0.dtd and
         * sun-web-app_2_3-0.dtd, and TRUE in the case of
         * sun-web-app_2_4-0.dtd.
         */

        SunWebXmlParser(ReadableArchive archive) throws XMLStreamException, IOException {
            super(archive);
        }


        @Override
        protected String getXmlFileName() {
            return SUN_WEB_XML;
        }


        protected String getRootElementName() {
            return "sun-web-app";
        }


        @Override
        protected void read(InputStream input) throws XMLStreamException {
            parser = getXMLInputFactory().createXMLStreamReader(input);

            int event = 0;
            boolean inClassLoader = false;
            skipRoot(getRootElementName());

            while (parser.hasNext() && (event = parser.next()) != END_DOCUMENT) {
                if (event == START_ELEMENT) {
                    String name = parser.getLocalName();
                    if ("class-loader".equals(name)) {
                        int count = parser.getAttributeCount();
                        for (int i = 0; i < count; i++) {
                            String attrName = parser.getAttributeName(i).getLocalPart();
                            if ("delegate".equals(attrName)) {
                                delegate = Boolean.valueOf(parser.getAttributeValue(i));
                            } else if ("extra-class-path".equals(attrName)) {
                                extraClassPath = parser.getAttributeValue(i);
                            } else if ("dynamic-reload-interval".equals(attrName)) {
                                if (parser.getAttributeValue(i) != null) {
                                    // Log warning if dynamic-reload-interval is specified
                                    // in sun-web.xml since it is not supported
                                    if (LOG.isLoggable(Level.WARNING)) {
                                        LOG.log(Level.WARNING, LogFacade.DYNAMIC_RELOAD_INTERVAL);
                                    }
                                }
                            }
                        }
                        inClassLoader = true;
                    } else if (inClassLoader && "property".equals(name)) {
                        int count = parser.getAttributeCount();
                        String propName = null;
                        String value = null;
                        for (int i = 0; i < count; i++) {
                            String attrName = parser.getAttributeName(i).getLocalPart();
                            if ("name".equals(attrName)) {
                                propName = parser.getAttributeValue(i);
                            } else if ("value".equals(attrName)) {
                                value = parser.getAttributeValue(i);
                            }
                        }

                        if (propName == null || value == null) {
                            throw new IllegalArgumentException(I18N.getString(LogFacade.NULL_WEB_PROPERTY));
                        }

                        if ("ignoreHiddenJarFiles".equals(propName)) {
                            ignoreHiddenJarFiles = Boolean.valueOf(value);
                        } else {
                            Object[] params = {propName, value};
                            if (LOG.isLoggable(Level.WARNING)) {
                                LOG.log(Level.WARNING, LogFacade.INVALID_PROPERTY, params);
                            }
                        }
                    } else if ("property".equals(name)) {
                        int count = parser.getAttributeCount();
                        String propName = null;
                        String value = null;
                        for (int i = 0; i < count; i++) {
                            String attrName = parser.getAttributeName(i).getLocalPart();
                            if ("name".equals(attrName)) {
                                propName = parser.getAttributeValue(i);
                            } else if ("value".equals(attrName)) {
                                value = parser.getAttributeValue(i);
                            }
                        }

                        if (propName == null || value == null) {
                            throw new IllegalArgumentException(I18N.getString(LogFacade.NULL_WEB_PROPERTY));
                        }

                        if ("useMyFaces".equalsIgnoreCase(propName)) {
                            useBundledJSF = Boolean.valueOf(value);
                        } else if ("useBundledJsf".equalsIgnoreCase(propName)) {
                            useBundledJSF = Boolean.valueOf(value);
                        }
                    } else if ("version-identifier".equals(name)) {
                        versionIdentifier = parser.getElementText();
                    } else {
                        skipSubTree(name);
                    }
                } else if (inClassLoader && event == END_ELEMENT) {
                    if ("class-loader".equals(parser.getLocalName())) {
                        inClassLoader = false;
                    }
                }
            }
        }
    }

    protected class GlassFishWebXmlParser extends SunWebXmlParser {

        GlassFishWebXmlParser(ReadableArchive archive) throws XMLStreamException, IOException {
            super(archive);
        }


        @Override
        protected String getXmlFileName() {
            return GLASSFISH_WEB_XML;
        }


        @Override
        protected String getRootElementName() {
            return "glassfish-web-app";
        }
    }

    protected class WeblogicXmlParser extends WebXmlParser {

        WeblogicXmlParser(ReadableArchive archive) throws XMLStreamException, IOException {
            super(archive);
        }


        @Override
        protected String getXmlFileName() {
            return WEBLOGIC_XML;
        }


        @Override
        protected void read(InputStream input) throws XMLStreamException {
            parser = getXMLInputFactory().createXMLStreamReader(input);

            skipRoot("weblogic-web-app");

            int event = 0;
            while (parser.hasNext() && (event = parser.next()) != END_DOCUMENT) {
                if (event == START_ELEMENT) {
                    String name = parser.getLocalName();
                    if ("prefer-web-inf-classes".equals(name)) {
                        // weblogic DD has default "false" for perfer-web-inf-classes
                        delegate = !Boolean.parseBoolean(parser.getElementText());
                        break;
                    } else if (!"container-descriptor".equals(name)) {
                        skipSubTree(name);
                    }
                }
            }
        }
    }

    protected class ContextXmlParser extends BaseXmlParser {

        protected Boolean clearReferencesStatic;

        ContextXmlParser(File contextXmlFile) throws XMLStreamException, IOException {
            if (contextXmlFile.exists()) {
                try (InputStream is = new FileInputStream(contextXmlFile)) {
                    init(is);
                }
            }
        }


        /**
         * This method will parse the input stream and set the XMLStreamReader
         * object for latter use.
         *
         * @param input InputStream
         * @exception XMLStreamException;
         */
        @Override
        protected void read(InputStream input) throws XMLStreamException {
            parser = getXMLInputFactory().createXMLStreamReader(input);

            int event = 0;
            while (parser.hasNext() && (event = parser.next()) != END_DOCUMENT) {
                if (event == START_ELEMENT) {
                    String name = parser.getLocalName();
                    if ("Context".equals(name)) {
                        String path = null;
                        Boolean crs = null;
                        int count = parser.getAttributeCount();
                        for (int i = 0; i < count; i++) {
                            String attrName = parser.getAttributeName(i).getLocalPart();
                            if ("clearReferencesStatic".equals(attrName)) {
                                crs = Boolean.valueOf(parser.getAttributeValue(i));
                            } else if ("path".equals(attrName)) {
                                path = parser.getAttributeValue(i);
                            }
                        }
                        if (path == null) { // make sure no path associated to it
                            clearReferencesStatic = crs;
                            break;
                        }
                    } else {
                        skipSubTree(name);
                    }
                }
            }
        }


        Boolean getClearReferencesStatic() {
            return clearReferencesStatic;
        }
    }
}
