/*
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
 * Copyright (c) 2010, 2018 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.concurrent.runtime.deployer;

import jakarta.inject.Inject;

import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.glassfish.api.naming.DefaultResourceProxy;
import org.glassfish.api.naming.NamedNamingObjectProxy;
import org.glassfish.api.naming.NamespacePrefixes;
import org.glassfish.concurrent.config.ManagedThreadFactory.ManagedThreadFactoryConfigActivator;
import org.jvnet.hk2.annotations.Service;

import static org.glassfish.api.naming.SimpleJndiName.JNDI_CTX_JAVA_COMPONENT;

/**
 * Naming Object Proxy to handle the Default ManagedThreadFactory.
 * Maps to a pre-configured managed thread factory, when binding for
 * a managed thread factory reference is absent in the @Resource annotation.
 */
@Service
@NamespacePrefixes({DefaultManagedThreadFactory.DEFAULT_MANAGED_THREAD_FACTORY})
public class DefaultManagedThreadFactory implements NamedNamingObjectProxy, DefaultResourceProxy {

    static final String DEFAULT_MANAGED_THREAD_FACTORY = JNDI_CTX_JAVA_COMPONENT + "DefaultManagedThreadFactory";
    private static final String DEFAULT_MANAGED_THREAD_FACTORY_PHYS = "concurrent/__defaultManagedThreadFactory";

    @Inject
    private ManagedThreadFactoryConfigActivator config;

    @Override
    public Object handle(String name) throws NamingException {
        return InitialContext.doLookup(DEFAULT_MANAGED_THREAD_FACTORY_PHYS);
    }


    @Override
    public String getPhysicalName() {
        return DEFAULT_MANAGED_THREAD_FACTORY_PHYS;
    }


    @Override
    public String getLogicalName() {
        return DEFAULT_MANAGED_THREAD_FACTORY;
    }
}
