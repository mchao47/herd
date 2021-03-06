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
package org.finra.dm.service.helper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import org.finra.dm.dao.DmDao;
import org.finra.dm.dao.S3Dao;
import org.finra.dm.dao.helper.DmCollectionHelper;
import org.finra.dm.model.ObjectNotFoundException;
import org.finra.dm.model.dto.S3FileTransferRequestParamsDto;
import org.finra.dm.model.jpa.BusinessObjectDataEntity;
import org.finra.dm.model.jpa.BusinessObjectDataStatusEntity;
import org.finra.dm.model.jpa.BusinessObjectFormatEntity;
import org.finra.dm.model.jpa.StorageEntity;
import org.finra.dm.model.jpa.StoragePlatformEntity;
import org.finra.dm.model.jpa.StorageUnitEntity;
import org.finra.dm.model.api.xml.BusinessObjectData;
import org.finra.dm.model.api.xml.BusinessObjectDataInvalidateUnregisteredRequest;
import org.finra.dm.model.api.xml.BusinessObjectDataInvalidateUnregisteredResponse;
import org.finra.dm.model.api.xml.BusinessObjectDataKey;
import org.finra.dm.model.api.xml.BusinessObjectFormatKey;
import org.finra.dm.service.SqsNotificationEventService;

@Component
public class BusinessObjectDataInvalidateUnregisteredHelper
{
    public static final String UNREGISTERED_STATUS = BusinessObjectDataStatusEntity.INVALID;

    @Autowired
    private DmDao dmDao;

    @Autowired
    private BusinessObjectDataHelper businessObjectDataHelper;

    @Autowired
    private S3Dao s3Dao;

    @Autowired
    private DmDaoHelper dmDaoHelper;

    @Autowired
    private DmCollectionHelper dmCollectionHelper;

    @Autowired
    private SqsNotificationEventService sqsNotificationEventService;

    @Autowired
    private StorageFileHelper storageFileHelper;

    /**
     * Compares objects registered vs what exists in S3. Registers objects in INVALID status for data that are not registered but exist in S3. S3 objects are
     * identified by DM's S3 key prefix.
     *
     * @param businessObjectDataInvalidateUnregisteredRequest the request
     *
     * @return response, optionally containing the data that have been registered.
     */
    public BusinessObjectDataInvalidateUnregisteredResponse invalidateUnregisteredBusinessObjectData(
        BusinessObjectDataInvalidateUnregisteredRequest businessObjectDataInvalidateUnregisteredRequest)
    {
        // Validate request
        validateRequest(businessObjectDataInvalidateUnregisteredRequest);

        // Trim
        trimRequest(businessObjectDataInvalidateUnregisteredRequest);

        // Validate format exists
        // Get format
        BusinessObjectFormatEntity businessObjectFormatEntity = getBusinessObjectFormatEntity(businessObjectDataInvalidateUnregisteredRequest);

        // Validate storage exists
        // Get storage by name
        String storageName = businessObjectDataInvalidateUnregisteredRequest.getStorageName();
        StorageEntity storageEntity = dmDaoHelper.getStorageEntity(storageName);

        // Validate that storage platform is S3
        if (!StoragePlatformEntity.S3.equals(storageEntity.getStoragePlatform().getName()))
        {
            throw new IllegalArgumentException("The specified storage '" + storageName + "' is not a S3 storage platform.");
        }

        // Get data with latest version
        BusinessObjectDataEntity latestBusinessObjectDataEntity = getLatestBusinessObjectDataEntity(businessObjectDataInvalidateUnregisteredRequest);
        Integer latestBusinessObjectDataVersion = getBusinessObjectDataVersion(latestBusinessObjectDataEntity);

        // List data which are not registered, but exists in S3
        List<BusinessObjectDataKey> unregisteredBusinessObjectDataKeys =
            getUnregisteredBusinessObjectDataKeys(businessObjectDataInvalidateUnregisteredRequest, storageEntity, businessObjectFormatEntity,
                latestBusinessObjectDataVersion);

        // Register the unregistered data as INVALID
        List<BusinessObjectDataEntity> registeredBusinessObjectDataEntities =
            registerInvalidBusinessObjectDatas(latestBusinessObjectDataEntity, businessObjectFormatEntity, unregisteredBusinessObjectDataKeys, storageEntity);

        // Fire notifications
        processBusinessObjectDataStatusChangeNotificationEvents(registeredBusinessObjectDataEntities);

        // Create and return response
        return getBusinessObjectDataInvalidateUnregisteredResponse(businessObjectDataInvalidateUnregisteredRequest, registeredBusinessObjectDataEntities);
    }

    /**
     * Fires business object data status changed notifications.
     *
     * @param businessObjectDataEntities list of business object data that were created.
     */
    private void processBusinessObjectDataStatusChangeNotificationEvents(List<BusinessObjectDataEntity> businessObjectDataEntities)
    {
        // Convert entities to key object
        List<BusinessObjectDataKey> registeredBusinessObjectDataKeys = new ArrayList<>();
        for (BusinessObjectDataEntity businessObjectDataEntity : businessObjectDataEntities)
        {
            BusinessObjectDataKey businessObjectDataKey = businessObjectDataHelper.createBusinessObjectDataKeyFromEntity(businessObjectDataEntity);
            registeredBusinessObjectDataKeys.add(businessObjectDataKey);
        }

        // Fire notifications on the keys
        for (BusinessObjectDataKey businessObjectDataKey : registeredBusinessObjectDataKeys)
        {
            sqsNotificationEventService.processBusinessObjectDataStatusChangeNotificationEvent(businessObjectDataKey, UNREGISTERED_STATUS, null);
        }
    }

    /**
     * Gets the version of the given business object data entity. Returns -1 if the entity is null.
     *
     * @param businessObjectDataEntity {@link BusinessObjectDataEntity} or null
     *
     * @return the version or -1
     */
    private Integer getBusinessObjectDataVersion(BusinessObjectDataEntity businessObjectDataEntity)
    {
        Integer businessObjectDataVersion = -1;
        if (businessObjectDataEntity != null)
        {
            businessObjectDataVersion = businessObjectDataEntity.getVersion();
        }
        return businessObjectDataVersion;
    }

    /**
     * Asserts that a format exists and gets the {@link BusinessObjectFormatEntity} from the given request.
     *
     * @param request {@link BusinessObjectDataInvalidateUnregisteredRequest} with format information
     *
     * @return {@link BusinessObjectFormatEntity}
     * @throws ObjectNotFoundException when the format does not exist
     */
    private BusinessObjectFormatEntity getBusinessObjectFormatEntity(BusinessObjectDataInvalidateUnregisteredRequest request)
    {
        BusinessObjectFormatKey businessObjectFormatKey = new BusinessObjectFormatKey();
        businessObjectFormatKey.setNamespace(request.getNamespace());
        businessObjectFormatKey.setBusinessObjectDefinitionName(request.getBusinessObjectDefinitionName());
        businessObjectFormatKey.setBusinessObjectFormatUsage(request.getBusinessObjectFormatUsage());
        businessObjectFormatKey.setBusinessObjectFormatFileType(request.getBusinessObjectFormatFileType());
        businessObjectFormatKey.setBusinessObjectFormatVersion(request.getBusinessObjectFormatVersion());

        return dmDaoHelper.getBusinessObjectFormatEntity(businessObjectFormatKey);
    }

    /**
     * Trims any relevant string values in the request.
     *
     * @param businessObjectDataInvalidateUnregisteredRequest request to trim
     */
    private void trimRequest(BusinessObjectDataInvalidateUnregisteredRequest businessObjectDataInvalidateUnregisteredRequest)
    {
        businessObjectDataInvalidateUnregisteredRequest.setNamespace(businessObjectDataInvalidateUnregisteredRequest.getNamespace().trim());
        businessObjectDataInvalidateUnregisteredRequest
            .setBusinessObjectDefinitionName(businessObjectDataInvalidateUnregisteredRequest.getBusinessObjectDefinitionName().trim());
        businessObjectDataInvalidateUnregisteredRequest
            .setBusinessObjectFormatUsage(businessObjectDataInvalidateUnregisteredRequest.getBusinessObjectFormatUsage().trim());
        businessObjectDataInvalidateUnregisteredRequest
            .setBusinessObjectFormatFileType(businessObjectDataInvalidateUnregisteredRequest.getBusinessObjectFormatFileType().trim());
        businessObjectDataInvalidateUnregisteredRequest.setPartitionValue(businessObjectDataInvalidateUnregisteredRequest.getPartitionValue().trim());
        businessObjectDataInvalidateUnregisteredRequest.setStorageName(businessObjectDataInvalidateUnregisteredRequest.getStorageName().trim());
        if (businessObjectDataInvalidateUnregisteredRequest.getSubPartitionValues() != null)
        {
            List<String> trimmedSubPartitionValues = new ArrayList<>();
            for (String subPartitionValue : businessObjectDataInvalidateUnregisteredRequest.getSubPartitionValues())
            {
                trimmedSubPartitionValues.add(subPartitionValue.trim());
            }
        }
    }

    /**
     * Validates that the required parameters are specified and are within acceptable range.
     *
     * @param businessObjectDataInvalidateUnregisteredRequest request to validate
     *
     * @throws IllegalArgumentException when any of the parameter fails valdiation
     */
    private void validateRequest(BusinessObjectDataInvalidateUnregisteredRequest businessObjectDataInvalidateUnregisteredRequest)
    {
        Assert.notNull(businessObjectDataInvalidateUnregisteredRequest, "The request is required");
        Assert.isTrue(StringUtils.isNotBlank(businessObjectDataInvalidateUnregisteredRequest.getNamespace()), "The namespace is required");
        Assert.isTrue(StringUtils.isNotBlank(businessObjectDataInvalidateUnregisteredRequest.getBusinessObjectDefinitionName()),
            "The business object definition name is required");
        Assert.isTrue(StringUtils.isNotBlank(businessObjectDataInvalidateUnregisteredRequest.getBusinessObjectFormatUsage()),
            "The business object format usage is required");
        Assert.isTrue(StringUtils.isNotBlank(businessObjectDataInvalidateUnregisteredRequest.getBusinessObjectFormatFileType()),
            "The business object format file type is required");
        Assert.notNull(businessObjectDataInvalidateUnregisteredRequest.getBusinessObjectFormatVersion(), "The business object format version is required");
        Assert.isTrue(businessObjectDataInvalidateUnregisteredRequest.getBusinessObjectFormatVersion() >= 0,
            "The business object format version must be greater than or equal to 0");
        Assert.isTrue(StringUtils.isNotBlank(businessObjectDataInvalidateUnregisteredRequest.getPartitionValue()), "The partition value is required");
        Assert.isTrue(StringUtils.isNotBlank(businessObjectDataInvalidateUnregisteredRequest.getStorageName()), "The storage name is required");
        if (businessObjectDataInvalidateUnregisteredRequest.getSubPartitionValues() != null)
        {
            for (int i = 0; i < businessObjectDataInvalidateUnregisteredRequest.getSubPartitionValues().size(); i++)
            {
                String subPartitionValue = businessObjectDataInvalidateUnregisteredRequest.getSubPartitionValues().get(i);
                Assert.isTrue(StringUtils.isNotBlank(subPartitionValue), "The sub-partition value [" + i + "] must not be blank");
            }
        }
    }

    /**
     * Constructs the response from the given request and the list of objects that have been registered.
     *
     * @param request the original request
     * @param registeredBusinessObjectDataEntities list of {@link BusinessObjectDataEntity} that have been newly created.
     *
     * @return {@link BusinessObjectDataInvalidateUnregisteredResponse}
     */
    private BusinessObjectDataInvalidateUnregisteredResponse getBusinessObjectDataInvalidateUnregisteredResponse(
        BusinessObjectDataInvalidateUnregisteredRequest request, List<BusinessObjectDataEntity> registeredBusinessObjectDataEntities)
    {
        BusinessObjectDataInvalidateUnregisteredResponse response = new BusinessObjectDataInvalidateUnregisteredResponse();
        response.setNamespace(request.getNamespace());
        response.setBusinessObjectDefinitionName(request.getBusinessObjectDefinitionName());
        response.setBusinessObjectFormatUsage(request.getBusinessObjectFormatUsage());
        response.setBusinessObjectFormatFileType(request.getBusinessObjectFormatFileType());
        response.setBusinessObjectFormatVersion(request.getBusinessObjectFormatVersion());
        response.setPartitionValue(request.getPartitionValue());
        response.setSubPartitionValues(request.getSubPartitionValues());
        response.setStorageName(request.getStorageName());
        response.setRegisteredBusinessObjectDataList(getResponseBusinessObjectDatas(registeredBusinessObjectDataEntities));
        return response;
    }

    /**
     * Constructs the response business object data from the given list of entities.
     *
     * @param registeredBusinessObjectDataEntities list of {@link BusinessObjectDataEntity}
     *
     * @return list of {@link BusinessObjectData} to be included in the response
     */
    private List<BusinessObjectData> getResponseBusinessObjectDatas(List<BusinessObjectDataEntity> registeredBusinessObjectDataEntities)
    {
        List<BusinessObjectData> responseBusinessObjectDatas = new ArrayList<>();
        for (BusinessObjectDataEntity businessObjectDataEntity : registeredBusinessObjectDataEntities)
        {
            BusinessObjectData responseBusinessObjectData = businessObjectDataHelper.createBusinessObjectDataFromEntity(businessObjectDataEntity);
            responseBusinessObjectDatas.add(responseBusinessObjectData);
        }
        return responseBusinessObjectDatas;
    }

    /**
     * Registers a business object data specified by the given business object data keys. The registered data will have a single storage unit with a directory
     * with the S3 key prefix of the data key. The registered data will be set to status INVALID. If any registration actually occurs, the specified
     * latestBusinessObjectDataEntity latestVersion will be set to false.
     * <p/>
     * The list of data keys must be in ordered by the data version in ascending order.
     *
     * @param latestBusinessObjectDataEntity the latest data at the time of the registration
     * @param businessObjectDataKeys the list of data to register, ordered by version
     * @param storageEntity the storage to register
     *
     * @return list of {@link BusinessObjectDataEntity} that have been registered
     */
    private List<BusinessObjectDataEntity> registerInvalidBusinessObjectDatas(BusinessObjectDataEntity latestBusinessObjectDataEntity,
        BusinessObjectFormatEntity businessObjectFormatEntity, List<BusinessObjectDataKey> businessObjectDataKeys, StorageEntity storageEntity)
    {
        List<BusinessObjectDataEntity> createdBusinessObjectDataEntities = new ArrayList<>();

        if (!businessObjectDataKeys.isEmpty())
        {
            if (latestBusinessObjectDataEntity != null)
            {
                // Set the latestVersion flag to false for the latest data. This data should no longer be marked as latest.
                latestBusinessObjectDataEntity.setLatestVersion(false);
            }

            Iterator<BusinessObjectDataKey> unregisteredBusinessObjectDataKeysIterator = businessObjectDataKeys.iterator();
            while (unregisteredBusinessObjectDataKeysIterator.hasNext())
            {
                BusinessObjectDataKey unregisteredBusinessObjectDataKey = unregisteredBusinessObjectDataKeysIterator.next();
                BusinessObjectDataEntity businessObjectDataEntity = new BusinessObjectDataEntity();
                businessObjectDataEntity.setBusinessObjectFormat(businessObjectFormatEntity);
                businessObjectDataEntity.setPartitionValue(unregisteredBusinessObjectDataKey.getPartitionValue());
                businessObjectDataEntity.setPartitionValue2(dmCollectionHelper.safeGet(unregisteredBusinessObjectDataKey.getSubPartitionValues(), 0));
                businessObjectDataEntity.setPartitionValue3(dmCollectionHelper.safeGet(unregisteredBusinessObjectDataKey.getSubPartitionValues(), 1));
                businessObjectDataEntity.setPartitionValue4(dmCollectionHelper.safeGet(unregisteredBusinessObjectDataKey.getSubPartitionValues(), 2));
                businessObjectDataEntity.setPartitionValue5(dmCollectionHelper.safeGet(unregisteredBusinessObjectDataKey.getSubPartitionValues(), 3));
                businessObjectDataEntity.setVersion(unregisteredBusinessObjectDataKey.getBusinessObjectDataVersion());
                List<StorageUnitEntity> storageUnitEntities = new ArrayList<>();
                StorageUnitEntity storageUnitEntity = new StorageUnitEntity();
                storageUnitEntity.setStorage(storageEntity);
                storageUnitEntity.setBusinessObjectData(businessObjectDataEntity);
                String s3KeyPrefix = businessObjectDataHelper.buildS3KeyPrefix(businessObjectFormatEntity, unregisteredBusinessObjectDataKey);
                storageUnitEntity.setDirectoryPath(s3KeyPrefix);
                storageUnitEntities.add(storageUnitEntity);
                businessObjectDataEntity.setStorageUnits(storageUnitEntities);
                businessObjectDataEntity.setStatus(dmDao.getBusinessObjectDataStatusByCode(UNREGISTERED_STATUS));

                // Set this data as latest version if this is the end of the loop
                businessObjectDataEntity.setLatestVersion(!unregisteredBusinessObjectDataKeysIterator.hasNext());

                dmDao.saveAndRefresh(businessObjectDataEntity);

                createdBusinessObjectDataEntities.add(businessObjectDataEntity);
            }
        }

        return createdBusinessObjectDataEntities;
    }

    /**
     * Returns a list of data keys that are not registered in DM, but exist in S3, for data versions after the latest data in the given request's format and
     * storage. Incrementally searches S3 for data versions until no results are found.
     *
     * @param request {@link BusinessObjectDataInvalidateUnregisteredRequest}
     * @param storageEntity {@link StorageEntity}
     * @param businessObjectFormatEntity {@link BusinessObjectFormatEntity}
     * @param latestRegisteredBusinessObjectDataVersion the latest registered business object data version.
     *
     * @return {@link BusinessObjectDataKey}
     */
    private List<BusinessObjectDataKey> getUnregisteredBusinessObjectDataKeys(BusinessObjectDataInvalidateUnregisteredRequest request,
        StorageEntity storageEntity, BusinessObjectFormatEntity businessObjectFormatEntity, Integer latestRegisteredBusinessObjectDataVersion)
    {
        // The result will be accumulated here
        List<BusinessObjectDataKey> unregisteredBusinessObjectDataKeys = new ArrayList<>();

        // Version offset from the latest version
        int businessObjectDataVersionOffset = 1;

        // Loop until no results are found in S3
        while (true)
        {
            // Get data key with incremented version
            BusinessObjectDataKey businessObjectDataKey = getBusinessObjectDataKey(request);
            businessObjectDataKey.setBusinessObjectDataVersion(latestRegisteredBusinessObjectDataVersion + businessObjectDataVersionOffset);

            // Find S3 object keys which match the prefix
            List<String> matchingS3ObjectKeys = getS3ObjectKeys(businessObjectFormatEntity, businessObjectDataKey, storageEntity);

            /*
             * If there are no matching keys, it means there are no objects registered for this version in S3.
             * If there are no matches, it means that this version is not out-of-sync with DM.
             */
            if (matchingS3ObjectKeys.isEmpty())
            {
                break;
            }

            // Add this data to result set
            unregisteredBusinessObjectDataKeys.add(businessObjectDataKey);

            // The next iteration of loop should check a higher version
            businessObjectDataVersionOffset++;
        }
        return unregisteredBusinessObjectDataKeys;
    }

    /**
     * Returns a list of S3 object keys associated with the given format, data key, and storage. The keys are found by matching the prefix. The result may be
     * empty if there are not matching keys found.
     *
     * @param businessObjectFormatEntity {@link BusinessObjectFormatEntity}
     * @param businessObjectDataKey {@link BusinessObjectDataKey}
     * @param storageEntity {@link StorageEntity}
     *
     * @return list of S3 object keys
     */
    private List<String> getS3ObjectKeys(BusinessObjectFormatEntity businessObjectFormatEntity, BusinessObjectDataKey businessObjectDataKey,
        StorageEntity storageEntity)
    {
        String s3KeyPrefix = businessObjectDataHelper.buildS3KeyPrefix(businessObjectFormatEntity, businessObjectDataKey);

        S3FileTransferRequestParamsDto s3FileTransferRequestParamsDto = dmDaoHelper.getS3BucketAccessParams(storageEntity);
        s3FileTransferRequestParamsDto.setS3KeyPrefix(s3KeyPrefix + '/');

        return storageFileHelper.getFilePaths(s3Dao.listDirectory(s3FileTransferRequestParamsDto));
    }

    /**
     * Returns the latest version of the business object data registered. Returns null if no data is registered.
     *
     * @param businessObjectDataInvalidateUnregisteredRequest request containing business object data key
     *
     * @return {@link BusinessObjectDataEntity} or null
     */
    private BusinessObjectDataEntity getLatestBusinessObjectDataEntity(
        BusinessObjectDataInvalidateUnregisteredRequest businessObjectDataInvalidateUnregisteredRequest)
    {
        BusinessObjectDataKey businessObjectDataKey = getBusinessObjectDataKey(businessObjectDataInvalidateUnregisteredRequest);
        businessObjectDataKey.setBusinessObjectDataVersion(null);
        return dmDao.getBusinessObjectDataByAltKey(businessObjectDataKey);
    }

    /**
     * Constructs a {@link BusinessObjectDataKey} from the given request. The returned key does not contain a data version.
     *
     * @param businessObjectDataInvalidateUnregisteredRequest the request with key information
     *
     * @return {@link BusinessObjectDataKey}
     */
    private BusinessObjectDataKey getBusinessObjectDataKey(BusinessObjectDataInvalidateUnregisteredRequest businessObjectDataInvalidateUnregisteredRequest)
    {
        BusinessObjectDataKey businessObjectDataKey = new BusinessObjectDataKey();
        businessObjectDataKey.setNamespace(businessObjectDataInvalidateUnregisteredRequest.getNamespace());
        businessObjectDataKey.setBusinessObjectDefinitionName(businessObjectDataInvalidateUnregisteredRequest.getBusinessObjectDefinitionName());
        businessObjectDataKey.setBusinessObjectFormatUsage(businessObjectDataInvalidateUnregisteredRequest.getBusinessObjectFormatUsage());
        businessObjectDataKey.setBusinessObjectFormatFileType(businessObjectDataInvalidateUnregisteredRequest.getBusinessObjectFormatFileType());
        businessObjectDataKey.setBusinessObjectFormatVersion(businessObjectDataInvalidateUnregisteredRequest.getBusinessObjectFormatVersion());
        businessObjectDataKey.setPartitionValue(businessObjectDataInvalidateUnregisteredRequest.getPartitionValue());
        businessObjectDataKey.setSubPartitionValues(businessObjectDataInvalidateUnregisteredRequest.getSubPartitionValues());
        if (businessObjectDataInvalidateUnregisteredRequest.getSubPartitionValues() == null)
        {
            businessObjectDataKey.setSubPartitionValues(new ArrayList<String>());
        }
        return businessObjectDataKey;
    }
}
