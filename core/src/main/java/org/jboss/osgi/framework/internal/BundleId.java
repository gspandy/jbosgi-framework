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

import org.osgi.framework.Bundle;

/**
 * The {@link Bundle} id
 *
 * @author thomas.diesler@jboss.com
 * @since 23-May-2011
 */
class BundleId {

    private final Long value;

    static BundleId parseLong(String value) {
        return new BundleId(Long.parseLong(value));
    }
    
    BundleId(long value) {
        this.value = new Long(value);
    }

    Long longValue() {
        return value;
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this)
            return true;
        if (!(obj instanceof BundleId))
            return false;
        BundleId other = (BundleId) obj;
        return value.equals(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}