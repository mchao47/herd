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
package org.finra.dm.service.activiti;

import java.io.Serializable;

import org.activiti.engine.runtime.ProcessInstance;

/**
 * A class that holds a handle to a process instance.
 */
public class ProcessInstanceHolder implements Serializable
{
    private static final long serialVersionUID = 880498730520310065L;

    private ProcessInstance processInstance;

    public ProcessInstance getProcessInstance()
    {
        return processInstance;
    }

    public void setProcessInstance(ProcessInstance processInstance)
    {
        this.processInstance = processInstance;
    }
}
