//  Copyright (c) 2007-2008 Adrian Kuhn <akuhn(a)iam.unibe.ch>
//  
//  This file is part of 'Fame (for Java)'.
//  
//  'Fame (for Java)' is free software: you can redistribute it and/or modify
//  it under the terms of the GNU Lesser General Public License as published by
//  the Free Software Foundation, either version 3 of the License, or (at your
//  option) any later version.
//  
//  'Fame (for Java)' is distributed in the hope that it will be useful, but
//  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
//  or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
//  License for more details.
//  
//  You should have received a copy of the GNU Lesser General Public License
//  along with 'Fame (for Java)'. If not, see <http://www.gnu.org/licenses/>.
//  

package ch.akuhn.fame.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ch.akuhn.fame.Repository;
import ch.akuhn.fame.fm3.Element;
import ch.akuhn.fame.fm3.MetaDescription;
import ch.akuhn.fame.fm3.PropertyDescription;
import ch.akuhn.fame.parser.ParseClient;
import ch.akuhn.util.query.Detect;
import ch.akuhn.util.query.Select;

/**
 * Accepts a {@link ParseClient} on a repository.
 * 
 * @author akuhn
 * 
 */
public class RepositoryVisitor implements Runnable {

    private Repository repo;
    private Map<Object, Integer> index;
    private ParseClient visitor;

    public RepositoryVisitor(Repository repo, ParseClient visitor) {
        this.repo = repo;
        this.visitor = visitor;
        this.index = new HashMap<Object,Integer>();
        int serial = 1;
        for (Object each : repo.getElements()) {
            index.put(each, serial++);
        }
    }

    private void acceptElement(Object each) {
        MetaDescription meta = repo.descriptionOf(each);
        visitor.beginElement(meta.getFullname());
        visitor.serial(getSerialNumber(meta, each));
        // XXX there can be more than one children property per element!
        PropertyDescription childrenProperty = childrenProperty(meta);
        assert childrenProperty == null || !childrenProperty.isContainer() : "Children property must not be a container!";
        for (PropertyDescription property : sortAttributes(meta.allAttributes())) {
            Collection<?> values = property.readAll(each);
            if (property.isDerived())
                continue;
            if (property.isContainer())
                continue;
            if (property.getType() == MetaDescription.BOOLEAN && !property.isMultivalued() && !values.isEmpty()) {
                if (!(Boolean) values.iterator().next())
                    continue;
            }
            if (!values.isEmpty()) {
                visitor.beginAttribute(property.getName());
                boolean isPrimitive = property.getType().isPrimitive();
                boolean isRoot = property.getType().isRoot();
                boolean isComposite = (property == childrenProperty);
                for (Object value : values) {
                    if (value instanceof MetaDescription) {
                        MetaDescription m = (MetaDescription) value;
                        if (m.isPrimitive() || m.isRoot()) {
                            visitor.reference(m.getName());
                            continue;
                        }
                    }
                    if (isPrimitive || (isRoot &&
                            (value instanceof String || 
                            value instanceof Boolean || 
                            value instanceof Number))) {
                        visitor.primitive(value);
                    } else {
                        if (isComposite) {
                            this.acceptElement(value);
                        } else {
                            Integer serial = getSerialNumber(property, value);
                            assert serial != null;
                            visitor.reference(serial);
                        }
                    }
                }
                visitor.endAttribute(property.getName());
            }
        }
        visitor.endElement(meta.getFullname());
    }
    
    /**
     * @param an element which was found by browsing the object-graph, but which
     * was not known to the repository yet.
     * @author tverwaes
     *
     */
    static public class UnknownElementError extends AssertionError {
        private static final long serialVersionUID = -6761765027263961388L;
        public Object unknown;
        public Element element;
        public UnknownElementError(Element element, Object unknown) {
            super("Unknown element: " + unknown + " found via description: " + element.getFullname());
            this.unknown = unknown;
            this.element = element;
        }
    }

    /** Returns the serial number of a given element
     * 
     * @param element an element of the current repository.
     * @throws AssertionError if the given object is not an element of the
     * current repository. This may happen, if a meta-described property refers
     * to objects that are not contained in the repository. Repositories must
     * be complete under transitive closure, that is, all objects reachable from
     * elements in a repository must be elements of the repository themselves. 
     * @return a unique serial number.
     */
    private int getSerialNumber(Element description, Object element) {
        Integer serial = index.get(element);
        if (serial == null) throw new UnknownElementError(description, element);
        return serial;
    }

    private void acceptVisitor() {
        visitor.beginDocument();
        Collection<Object> elements = rootElements(repo);
        elements = removeBuiltinMetaDescriptions(elements);
        for (Object each : elements) {
            this.acceptElement(each);
        }
        visitor.endDocument();
    }

    private PropertyDescription childrenProperty(MetaDescription meta) {
        Detect<PropertyDescription> query = Detect.from(meta.getAttributes());
        for (Detect<PropertyDescription> each: query) {
            each.yield = each.element.isComposite();
        }
        return query.resultIfNone(null);
//        return Blocks.detect(meta.getAttributes(), new Predicate<PropertyDescription>() {
//            public boolean apply(PropertyDescription each) {
//                return each.isComposite();
//            }
//        });
    }

    private Collection<Object> removeBuiltinMetaDescriptions(Collection<Object> elements) {
        Set<Object> copy = new HashSet<Object>(elements);
        copy.remove(MetaDescription.OBJECT);
        copy.remove(MetaDescription.STRING);
        copy.remove(MetaDescription.NUMBER);
        copy.remove(MetaDescription.BOOLEAN);
        copy.remove(MetaDescription.DATE);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Collection<Object> rootElements(final Repository m) {
        Select<Object> query = Select.from(m.getElements());
        for (Select<Object> each: query) {
            MetaDescription meta = m.descriptionOf(each.element);
            PropertyDescription containerProperty = meta.containerPropertyOrNull();
            if (containerProperty != null) {
                Object container = containerProperty.read(each.element);
                if (container != null) continue;
            }
            each.yield = true;
        };
        return query.result();
    }

    public void run() {
        assert index != null : "Can not run the same visitor twice.";
        this.acceptVisitor();
        index = null;
    }

    private Collection<PropertyDescription> sortAttributes(Collection<PropertyDescription> properties) {
        List<PropertyDescription> sorted = new ArrayList<PropertyDescription>(properties);
        Collections.sort(sorted, new Comparator<PropertyDescription>() {
            public int compare(PropertyDescription a, PropertyDescription b) {
                String a0 = a.getName();
                String b0 = b.getName();
                if (a0.equals(b0))
                    return 0;
                if (a0.equals("name"))
                    return -1;
                if (b0.equals("name"))
                    return +1;
                if (!a.isComposite() && b.isComposite())
                    return -1;
                if (a.isComposite() && !b.isComposite())
                    return +1;
                return a0.compareTo(b0);
            }
        });
        return sorted;
    }

}
