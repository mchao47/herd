/*
* Copyright 2015 herd contributors
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.finra.dm.model.jpa;

import javax.persistence.Column;
import javax.persistence.DiscriminatorColumn;
import javax.persistence.DiscriminatorType;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Inheritance;
import javax.persistence.InheritanceType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;

/**
 * A base entity class for notification registration.
 */
@Table(name = NotificationRegistrationEntity.TABLE_NAME)
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "ntfcn_type_cd", discriminatorType = DiscriminatorType.STRING)
public class NotificationRegistrationEntity extends AuditableEntity
{
    /**
     * The table name.
     */
    public static final String TABLE_NAME = "ntfcn_rgstn";

    @Id
    @Column(name = TABLE_NAME + "_id")
    @GeneratedValue(generator = TABLE_NAME + "_seq")
    @SequenceGenerator(name = TABLE_NAME + "_seq", sequenceName = TABLE_NAME + "_seq")
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "name_space_cd", referencedColumnName = "name_space_cd", nullable = false)
    private NamespaceEntity namespace;

    /**
     * The name column.
     */
    @Column(name = "name_tx", nullable = false)
    private String name;

    @ManyToOne
    @JoinColumn(name = "ntfcn_event_type_cd", referencedColumnName = "ntfcn_event_type_cd", nullable = false)
    private NotificationEventTypeEntity notificationEventType;

    public Integer getId()
    {
        return id;
    }

    public void setId(Integer id)
    {
        this.id = id;
    }

    public NamespaceEntity getNamespace()
    {
        return namespace;
    }

    public void setNamespace(NamespaceEntity namespace)
    {
        this.namespace = namespace;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public NotificationEventTypeEntity getNotificationEventType()
    {
        return notificationEventType;
    }

    public void setNotificationEventType(NotificationEventTypeEntity notificationEventType)
    {
        this.notificationEventType = notificationEventType;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (o == null || getClass() != o.getClass())
        {
            return false;
        }

        NotificationRegistrationEntity that = (NotificationRegistrationEntity) o;

        if (id != null ? !id.equals(that.id) : that.id != null)
        {
            return false;
        }
        if (name != null ? !name.equals(that.name) : that.name != null)
        {
            return false;
        }
        if (namespace != null ? !namespace.equals(that.namespace) : that.namespace != null)
        {
            return false;
        }
        if (notificationEventType != null ? !notificationEventType.equals(that.notificationEventType) : that.notificationEventType != null)
        {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode()
    {
        int result = id != null ? id.hashCode() : 0;
        result = 31 * result + (namespace != null ? namespace.hashCode() : 0);
        result = 31 * result + (name != null ? name.hashCode() : 0);
        result = 31 * result + (notificationEventType != null ? notificationEventType.hashCode() : 0);
        return result;
    }
}
