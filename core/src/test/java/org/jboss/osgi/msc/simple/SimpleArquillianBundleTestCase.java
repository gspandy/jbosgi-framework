/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.osgi.msc.simple;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.InputStream;

import javax.inject.Inject;

import org.jboss.arquillian.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.osgi.msc.simple.bundle.SimpleActivator;
import org.jboss.osgi.msc.simple.bundle.SimpleService;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.osgi.testing.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.Archives;
import org.jboss.shrinkwrap.api.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;

/**
 * A test that deployes a bundle and verifies its state
 * 
 * @author thomas.diesler@jboss.com
 * @since 18-Aug-2009
 */
@Ignore
@RunWith(Arquillian.class)
public class SimpleArquillianBundleTestCase extends OSGiFrameworkTest 
{
   @Deployment
   public static JavaArchive createdeployment()
   {
      // Bundle-Version: 1.0.0
      // Bundle-SymbolicName: simple-bundle
      // Bundle-Activator: org.jboss.osgi.msc.framework.simple.bundle.SimpleActivator
      final JavaArchive archive = Archives.create("simple-bundle", JavaArchive.class);
      archive.addClasses(SimpleService.class, SimpleActivator.class);
      archive.setManifest(new Asset()
      {
         public InputStream openStream()
         {
            OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
            builder.addBundleManifestVersion(2);
            builder.addBundleSymbolicName(archive.getName());
            builder.addBundleVersion("1.0.0");
            builder.addBundleActivator(SimpleActivator.class);
            return builder.openStream();
         }
      });
      return archive;
   }
   
   @Inject
   public Bundle bundle;
   
   @Test
   public void testBundleLifecycle() throws Exception
   {
      assertEquals("simple-bundle", bundle.getSymbolicName());
      assertEquals(Version.parseVersion("1.0.0"), bundle.getVersion());
      assertEquals("Bundle state", Bundle.RESOLVED, bundle.getState());

      bundle.start();
      assertEquals("Bundle state", Bundle.ACTIVE, bundle.getState());

      BundleContext context = bundle.getBundleContext();
      assertNotNull("BundleContext not null", context);

      // getServiceReference from bundle context
      //ServiceReference sref = context.getServiceReference(SimpleService.class.getName());
      //assertNotNull("ServiceReference not null", sref);

      bundle.stop();
      assertEquals("Bundle state", Bundle.RESOLVED, bundle.getState());
      
      bundle.uninstall();
      assertEquals("Bundle state", Bundle.UNINSTALLED, bundle.getState());
   }
}