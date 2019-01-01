//package com.persistence.model;
//
//import java.util.Collection;
//
//import javax.persistence.Entity;
//import javax.persistence.GeneratedValue;
//import javax.persistence.GenerationType;
//import javax.persistence.Id;
//import javax.persistence.ManyToMany;
//import javax.persistence.Table;
//
//@Entity
//@Table(name = "servie")
//public class Service {
//
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;
//
//    private String name;
//
////    @ManyToMany(mappedBy = "services")
////    private Collection<Type> types;
//
//    public Service() {
//        super();
//    }
//
//    public Service(final String name) {
//        super();
//        this.name = name;
//    }
//
//    //
//
//    public Long getId() {
//        return id;
//    }
//
//    public void setId(final Long id) {
//        this.id = id;
//    }
//
//    public String getName() {
//        return name;
//    }
//
//    public void setName(final String name) {
//        this.name = name;
//    }
////
////    public Collection<Type> getTypes() {
////        return types;
////    }
////
////    public void setTypes(final Collection<Type> types) {
////        this.types = types;
////    }
//
//    @Override
//    public int hashCode() {
//        final int prime = 31;
//        int result = 1;
//        result = prime * result + ((name == null) ? 0 : name.hashCode());
//        return result;
//    }
//
//    @Override
//    public boolean equals(Object obj) {
//        if (this == obj)
//            return true;
//        if (obj == null)
//            return false;
//        if (getClass() != obj.getClass())
//            return false;
//        Service other = (Service) obj;
//        if (name == null) {
//            if (other.name != null)
//                return false;
//        } else if (!name.equals(other.name))
//            return false;
//        return true;
//    }
//
//    @Override
//    public String toString() {
//        final StringBuilder builder = new StringBuilder();
//        builder.append("Service [name=").append(name).append("]").append("[id=").append(id).append("]");
//        return builder.toString();
//    }
//}
