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
package org.jboss.osgi.framework.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.logging.Logger;
import org.jboss.modules.ModuleClassLoader;
import org.jboss.msc.service.AbstractService;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.jboss.osgi.framework.ServiceNames;
import org.jboss.osgi.framework.util.NoFilter;
import org.jboss.osgi.framework.util.RemoveOnlyCollection;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.hooks.service.FindHook;
import org.osgi.framework.hooks.service.ListenerHook;
import org.osgi.framework.hooks.service.ListenerHook.ListenerInfo;

/**
 * A plugin that manages OSGi services
 *
 * @author thomas.diesler@jboss.com
 * @since 18-Aug-2009
 */
final class ServiceManagerPlugin extends AbstractPluginService<ServiceManagerPlugin> {

    // Provide logging
    private final Logger log = Logger.getLogger(ServiceManagerPlugin.class);

    private final InjectedValue<BundleManager> injectedBundleManager = new InjectedValue<BundleManager>();
    private final InjectedValue<FrameworkEventsPlugin> injectedFrameworkEvents = new InjectedValue<FrameworkEventsPlugin>();
    private final InjectedValue<ModuleManagerPlugin> injectedModuleManager = new InjectedValue<ModuleManagerPlugin>();

    // The ServiceId generator
    private AtomicLong identityGenerator = new AtomicLong();
    // The cached service container
    private ServiceContainer serviceContainer;
    // The cached service target for all child services
    private ServiceTarget serviceTarget;

    static void addService(ServiceTarget serviceTarget) {
        ServiceManagerPlugin service = new ServiceManagerPlugin();
        ServiceBuilder<ServiceManagerPlugin> builder = serviceTarget.addService(InternalServices.SERVICE_MANAGER_PLUGIN, service);
        builder.addDependency(ServiceNames.BUNDLE_MANAGER, BundleManager.class, service.injectedBundleManager);
        builder.addDependency(InternalServices.FRAMEWORK_EVENTS_PLUGIN, FrameworkEventsPlugin.class, service.injectedFrameworkEvents);
        builder.addDependency(InternalServices.MODULE_MANGER_PLUGIN, ModuleManagerPlugin.class, service.injectedModuleManager);
        builder.setInitialMode(Mode.ON_DEMAND);
        builder.install();
    }

    private ServiceManagerPlugin() {
    }

    @Override
    public void start(StartContext context) throws StartException {
        super.start(context);
        serviceContainer = context.getController().getServiceContainer();
        serviceTarget = context.getChildTarget();
    }

    @Override
    public void stop(StopContext context) {
        super.stop(context);
    }

    @Override
    public ServiceManagerPlugin getValue() {
        return this;
    }

    FrameworkEventsPlugin getFrameworkEventsPlugin() {
        return injectedFrameworkEvents.getValue();
    }

    /**
     * Get the next service ID from the manager
     */
    long getNextServiceId() {
        return identityGenerator.incrementAndGet();
    }

    /**
     * Registers the specified service object with the specified properties under the specified class names into the Framework.
     * A <code>ServiceRegistration</code> object is returned. The <code>ServiceRegistration</code> object is for the private use
     * of the bundle registering the service and should not be shared with other bundles. The registering bundle is defined to
     * be the context bundle.
     *
     * @param clazzes The class names under which the service can be located.
     * @param service The service object or a <code>ServiceFactory</code> object.
     * @param properties The properties for this service.
     * @return A <code>ServiceRegistration</code> object for use by the bundle registering the service
     */
    @SuppressWarnings({ "rawtypes" })
    ServiceState registerService(final BundleState bundleState, final String[] clazzes, final Object serviceValue, final Dictionary properties) {
        if (clazzes == null || clazzes.length == 0)
            throw new IllegalArgumentException("Null service classes");

        // Immediately after registration of a {@link ListenerHook}, the ListenerHook.added() method will be called
        // to provide the current collection of service listeners which had been added prior to the hook being registered.
        FrameworkEventsPlugin eventsPlugin = getFrameworkEventsPlugin();
        Collection<ListenerInfo> listenerInfos = null;
        if (serviceValue instanceof ListenerHook) {
            listenerInfos = eventsPlugin.getServiceListenerInfos(null);
        }

        ServiceState.ValueProvider valueProvider = new ServiceState.ValueProvider() {
            public Object getValue() {
                return serviceValue;
            }
        };

        long serviceId = getNextServiceId();
        ServiceState serviceState = new ServiceState(this, bundleState, serviceId, clazzes, valueProvider, properties);
        log.debugf("Register service: %s", serviceState);

        for (ServiceName serviceName : serviceState.getServiceNames()) {
            @SuppressWarnings("unchecked")
            ServiceController<List<ServiceState>> controller = (ServiceController<List<ServiceState>>) serviceContainer.getService(serviceName);
            if (controller != null) {
                List<ServiceState> serviceStates = controller.getValue();
                serviceStates.add(serviceState);
            } else {
                final List<ServiceState> serviceStates = new CopyOnWriteArrayList<ServiceState>();
                serviceStates.add(serviceState);
                Service<List<ServiceState>> service = new AbstractService<List<ServiceState>>() {
                    public List<ServiceState> getValue() throws IllegalStateException {
                        // [TODO] for injection to work this needs to be the Object value
                        return serviceStates;
                    }
                };
                ServiceBuilder<List<ServiceState>> builder = serviceTarget.addService(serviceName, service);
                builder.install();
            }
        }
        bundleState.addRegisteredService(serviceState);

        // Call the newly added ListenerHook.added() method
        if (serviceValue instanceof ListenerHook) {
            ListenerHook listenerHook = (ListenerHook) serviceValue;
            listenerHook.added(listenerInfos);
        }

        // This event is synchronously delivered after the service has been registered with the Framework.
        eventsPlugin.fireServiceEvent(bundleState, ServiceEvent.REGISTERED, serviceState);

        return serviceState;
    }

    /**
     * Returns a <code>ServiceReference</code> object for a service that implements and was registered under the specified
     * class.
     *
     * @param clazz The class name with which the service was registered.
     * @return A <code>ServiceReference</code> object, or <code>null</code>
     */
    ServiceState getServiceReference(BundleState bundleState, String clazz) {
        if (clazz == null)
            throw new IllegalArgumentException("Null clazz");

        boolean checkAssignable = (bundleState.getBundleId() != 0);
        List<ServiceState> result = getServiceReferencesInternal(bundleState, clazz, NoFilter.INSTANCE, checkAssignable);
        result = processFindHooks(bundleState, clazz, null, true, result);
        if (result.isEmpty())
            return null;

        int lastIndex = result.size() - 1;
        return result.get(lastIndex);
    }

    /**
     * Returns an array of <code>ServiceReference</code> objects. The returned array of <code>ServiceReference</code> objects
     * contains services that were registered under the specified class, match the specified filter expression.
     *
     * If checkAssignable is true, the packages for the class names under which the services were registered match the context
     * bundle's packages as defined in {@link ServiceReference#isAssignableTo(Bundle, String)}.
     *
     *
     * @param clazz The class name with which the service was registered or <code>null</code> for all services.
     * @param filter The filter expression or <code>null</code> for all services.
     * @return A potentially empty list of <code>ServiceReference</code> objects.
     */
    List<ServiceState> getServiceReferences(BundleState bundleState, String clazz, String filterStr, boolean checkAssignable) throws InvalidSyntaxException {
        Filter filter = NoFilter.INSTANCE;
        if (filterStr != null)
            filter = FrameworkUtil.createFilter(filterStr);

        List<ServiceState> result = getServiceReferencesInternal(bundleState, clazz, filter, checkAssignable);
        result = processFindHooks(bundleState, clazz, filterStr, checkAssignable, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<ServiceState> getServiceReferencesInternal(final BundleState bundleState, final String clazz, final Filter filter, boolean checkAssignable) {
        if (bundleState == null)
            throw new IllegalArgumentException("Null bundleState");
        if (filter == null)
            throw new IllegalArgumentException("Null filter");

        Set<ServiceName> serviceNames = new HashSet<ServiceName>();
        if (clazz != null) {
            ServiceName serviceName = ServiceState.createServiceName(clazz);
            if (serviceContainer.getService(serviceName) != null) {
                serviceNames.add(serviceName);
            } else {
                ServiceName xserviceName = ServiceState.createXServiceName(clazz);
                if (serviceContainer.getService(xserviceName) != null) {
                    serviceNames.add(xserviceName);
                }
            }
        } else {
            for (ServiceName aux : serviceContainer.getServiceNames()) {
                if (ServiceNames.JBOSGI_SERVICE_BASE_NAME.isParentOf(aux) || ServiceNames.JBOSGI_XSERVICE_BASE_NAME.isParentOf(aux)) {
                    serviceNames.add(aux);
                }
            }
        }

        if (serviceNames.isEmpty())
            return Collections.emptyList();

        List<ServiceState> result = new ArrayList<ServiceState>();
        for (ServiceName serviceName : serviceNames) {
            final ServiceController<?> controller = serviceContainer.getService(serviceName);
            if (controller != null) {
                if (ServiceNames.JBOSGI_SERVICE_BASE_NAME.isParentOf(serviceName)) {
                    for (ServiceState serviceState : (List<ServiceState>) controller.getValue()) {
                        if (filter.match(serviceState)) {
                            Object rawValue = serviceState.getRawValue();
                            checkAssignable &= (clazz != null);
                            checkAssignable &= (bundleState.getBundleId() != 0);
                            checkAssignable &= !(rawValue instanceof ServiceFactory);
                            if (checkAssignable == false || serviceState.isAssignableTo(bundleState, clazz)) {
                                result.add(serviceState);
                            }
                        }
                    }
                } else if (ServiceNames.JBOSGI_XSERVICE_BASE_NAME.isParentOf(serviceName)) {
                    final ServiceState.ValueProvider valueProvider = new ServiceState.ValueProvider() {
                        @Override
                        public Object getValue() {
                            ModuleClassLoader classLoader = bundleState.getCurrentRevision().getModuleClassLoader();
                            ClassLoader ctxLoader = SecurityActions.getContextClassLoader();
                            try {
                                SecurityActions.setContextClassLoader(classLoader);
                                return controller.getValue();
                            } finally {
                                SecurityActions.setContextClassLoader(ctxLoader);
                            }
                        }
                    };
                    final long serviceId = getNextServiceId();
                    final Object value = valueProvider.getValue();
                    final BundleState aux = injectedModuleManager.getValue().getBundleState(value.getClass());
                    final BundleState owner = (aux != null ? aux : injectedBundleManager.getValue().getSystemBundle());
                    ServiceState serviceState = new ServiceState(this, owner, serviceId, new String[] { clazz }, valueProvider, null);
                    if (filter.match(serviceState)) {
                        Object rawValue = serviceState.getRawValue();
                        checkAssignable &= (clazz != null);
                        checkAssignable &= (bundleState.getBundleId() != 0);
                        checkAssignable &= !(rawValue instanceof ServiceFactory);
                        if (checkAssignable == false || serviceState.isAssignableTo(bundleState, clazz)) {
                            result.add(serviceState);
                        }
                    }
                }
            }
        }

        // Sort the result
        if (result.size() > 1)
            Collections.sort(result, ServiceReferenceComparator.getInstance());

        return Collections.unmodifiableList(result);
    }

    /**
     * Returns the service object referenced by the specified <code>ServiceReference</code> object.
     *
     * @param reference A reference to the service.
     * @return A service object for the service associated with <code>reference</code> or <code>null</code>
     */
    Object getService(BundleState bundleState, ServiceState serviceState) {
        // If the service has been unregistered, null is returned.
        if (serviceState.isUnregistered())
            return null;

        // Add the given service ref to the list of used services
        bundleState.addServiceInUse(serviceState);
        serviceState.addUsingBundle(bundleState);

        Object value = serviceState.getScopedValue(bundleState);

        // If the factory returned an invalid value
        // restore the service usage counts
        if (value == null) {
            bundleState.removeServiceInUse(serviceState);
            serviceState.removeUsingBundle(bundleState);
        }

        return value;
    }

    /**
     * Unregister the given service.
     */
    @SuppressWarnings("unchecked")
    void unregisterService(ServiceState serviceState) {
        synchronized (serviceState) {

            if (serviceState.isUnregistered())
                return;

            for (ServiceName serviceName : serviceState.getServiceNames()) {
                log.debugf("Unregister service: %s", serviceName);
                try {
                    ServiceController<?> controller = serviceContainer.getService(serviceName);
                    if (controller != null) {
                        List<ServiceState> serviceStates = (List<ServiceState>) controller.getValue();
                        serviceStates.remove(serviceState);
                        if (serviceStates.isEmpty()) {
                            controller.setMode(Mode.REMOVE);
                        }
                    }
                } catch (RuntimeException ex) {
                    log.errorf(ex, "Cannot remove service: %s", serviceName);
                }
            }

            BundleState serviceOwner = serviceState.getServiceOwner();

            // This event is synchronously delivered before the service has completed unregistering.
            FrameworkEventsPlugin eventsPlugin = injectedFrameworkEvents.getValue();
            eventsPlugin.fireServiceEvent(serviceOwner, ServiceEvent.UNREGISTERING, serviceState);

            // Remove from using bundles
            for (BundleState bundleState : serviceState.getUsingBundlesInternal()) {
                while (ungetService(bundleState, serviceState)) {
                }
            }

            // Remove from owner bundle
            serviceOwner.removeRegisteredService(serviceState);
        }
    }

    /**
     * Releases the service object referenced by the specified <code>ServiceReference</code> object. If the context bundle's use
     * count for the service is zero, this method returns <code>false</code>. Otherwise, the context bundle's use count for the
     * service is decremented by one.
     *
     * @param reference A reference to the service to be released.
     * @return <code>false</code> if the context bundle's use count for the service is zero or if the service has been
     *         unregistered; <code>true</code> otherwise.
     */
    boolean ungetService(BundleState bundleState, ServiceState serviceState) {
        serviceState.ungetScopedValue(bundleState);

        int useCount = bundleState.removeServiceInUse(serviceState);
        if (useCount == 0)
            serviceState.removeUsingBundle(bundleState);

        return useCount >= 0;
    }

    /*
     * The FindHook is called when a target bundle searches the service registry with the getServiceReference or
     * getServiceReferences methods. A registered FindHook service gets a chance to inspect the returned set of service
     * references and can optionally shrink the set of returned services. The order in which the find hooks are called is the
     * reverse compareTo ordering of their Service References.
     */
    private List<ServiceState> processFindHooks(BundleState bundle, String clazz, String filterStr, boolean checkAssignable, List<ServiceState> serviceStates) {
        BundleContext context = bundle.getBundleContext();
        List<ServiceState> hookRefs = getServiceReferencesInternal(bundle, FindHook.class.getName(), NoFilter.INSTANCE, true);
        if (hookRefs.isEmpty())
            return serviceStates;

        // Event and Find Hooks can not be used to hide the services from the framework.
        if (clazz != null && clazz.startsWith(FindHook.class.getPackage().getName()))
            return serviceStates;

        // The order in which the find hooks are called is the reverse compareTo ordering of
        // their ServiceReferences. That is, the service with the highest ranking number must be called first.
        List<ServiceReference> sortedHookRefs = new ArrayList<ServiceReference>(hookRefs);
        Collections.reverse(sortedHookRefs);

        List<FindHook> hooks = new ArrayList<FindHook>();
        for (ServiceReference hookRef : sortedHookRefs)
            hooks.add((FindHook) context.getService(hookRef));

        Collection<ServiceReference> hookParam = new ArrayList<ServiceReference>();
        for (ServiceState aux : serviceStates)
            hookParam.add(aux.getReference());

        hookParam = new RemoveOnlyCollection<ServiceReference>(hookParam);
        for (FindHook hook : hooks) {
            try {
                hook.find(context, clazz, filterStr, !checkAssignable, hookParam);
            } catch (Exception ex) {
                log.warnf(ex, "Error while calling FindHook: %s", hook);
            }
        }

        List<ServiceState> result = new ArrayList<ServiceState>();
        for (ServiceReference aux : hookParam) {
            ServiceState serviceState = ServiceState.assertServiceState(aux);
            result.add(serviceState);
        }

        return result;
    }
}