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
package org.jboss.test.osgi.modules;

import org.jboss.modules.DependencySpec;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleSpec;
import org.jboss.modules.ResourceLoaderSpec;
import org.jboss.modules.filter.ClassFilter;
import org.jboss.modules.filter.PathFilter;
import org.jboss.modules.filter.PathFilters;
import org.jboss.osgi.framework.util.VirtualFileResourceLoader;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.modules.a.BarImpl;
import org.jboss.test.osgi.modules.a.QuxBar;
import org.jboss.test.osgi.modules.a.QuxFoo;
import org.jboss.test.osgi.modules.a.QuxImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * [MODULES-69] Allow for OSGi style Class Filtering
 * 
 * @author Thomas.Diesler@jboss.com
 * @since 28-Apr-2011
 */
public class MOD69TestCase extends ModulesTestBase {

    private VirtualFile virtualFileA;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        virtualFileA = toVirtualFile(getModuleA());
    }

    @After
    public void tearDown() throws Exception {
        VFSUtils.safeClose(virtualFileA);
    }

    @Test
    public void testClassFilter() throws Exception {
        JavaArchive archiveA = getModuleA();
        final ModuleIdentifier identifierA = ModuleIdentifier.create(archiveA.getName());

        ModuleSpec.Builder specBuilderA = ModuleSpec.build(identifierA);
        VirtualFileResourceLoader resourceLoaderA = new VirtualFileResourceLoader(virtualFileA);

        // Export-Package: com.acme.foo; include:="Qux*,BarImpl";exclude:=QuxImpl

        String packagePath = QuxBar.class.getPackage().getName();
        PathFilter inA = PathFilters.match(packagePath + ".Qux*");
        PathFilter inB = PathFilters.match(packagePath + ".BarImpl");
        PathFilter exA = PathFilters.match(packagePath + ".QuxImpl");

        //A class is only visible if it is:
        //    Matched with an entry in the included list, and
        //    Not matched with an entry in the excluded list.

        PathFilter in = PathFilters.any(inA, inB);
        PathFilter ex = PathFilters.not(PathFilters.any(exA));
        final PathFilter filter = PathFilters.all(in, ex);

        ClassFilter classImportFilter = new ClassFilter() {
            public boolean accept(String className) {
                return true;
            }
        };
        ClassFilter classExportFilter = new ClassFilter() {
            public boolean accept(String className) {
                return filter.accept(className);
            }
        };
        specBuilderA.addResourceRoot(ResourceLoaderSpec.createResourceLoaderSpec(resourceLoaderA));
        PathFilter importFilter = PathFilters.acceptAll();
        PathFilter exportFilter = PathFilters.acceptAll();
        PathFilter resourceImportFilter = PathFilters.acceptAll();
        PathFilter resourceExportFilter = PathFilters.acceptAll();
        specBuilderA.addDependency(DependencySpec.createLocalDependencySpec(importFilter, exportFilter, resourceImportFilter, resourceExportFilter, classImportFilter, classExportFilter));
        addModuleSpec(specBuilderA.create());

        ModuleIdentifier identifierB = ModuleIdentifier.create("moduleB");
        ModuleSpec.Builder specBuilderB = ModuleSpec.build(identifierB);
        specBuilderB.addDependency(DependencySpec.createModuleDependencySpec(identifierA));
        addModuleSpec(specBuilderB.create());

        assertLoadClass(identifierA, QuxFoo.class.getName());
        assertLoadClass(identifierA, QuxBar.class.getName());
        assertLoadClass(identifierA, QuxImpl.class.getName());
        assertLoadClass(identifierA, BarImpl.class.getName());

        assertLoadClass(identifierB, QuxFoo.class.getName());
        assertLoadClass(identifierB, QuxBar.class.getName());
        assertLoadClassFail(identifierB, QuxImpl.class.getName());
        assertLoadClass(identifierB, BarImpl.class.getName());
    }

    private JavaArchive getModuleA() {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "moduleA");
        archive.addClasses(QuxBar.class, QuxFoo.class, QuxImpl.class, BarImpl.class);
        return archive;
    }
}
