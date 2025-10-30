/*
 * Copyright 2023 Andrew Crutchlow
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.nimrodtechs.ipcrsock.subscriber;

import com.nimrodtechs.ipcrsock.common.PublisherPayload;

/**
 * There should never be more than 2 entries in a queue...the one being worked
 * on and the latest arrived. Every message for same subject gets processed
 * sequentially by same thread in the event there is a backlog... or delegate to
 * a new thread if there is no current thread for the subject.
 * Note : a wildcard subscription will be processed in one queue therefore will be subject to conflation, which might not be what
 * you actually want in which case don't use ConflatingQueue for a wildcard subscription.
 *
 * @author andy
 */
public class ConflatingExecutor extends QueueExecutor {

    public ConflatingExecutor(String threadNamePrefix) {
        super(threadNamePrefix);
    }

    @Override
    public void process(PublisherPayload publisherPayload, MessageProcessorEntry mpe) {
        // This is the conflating flavor so as a result of this there can only
        // be 1 or 2 entries i.e. if there
        // is one already the
//        if (mpe.getInProgressIndicator().compareAndSet(false, true)) {
//            mpe.conflatedMessages[0] = publisherPayload;
//            mpe.conflatedMessages[1] = null;
//            // A current thread is not inprogress so start one
//            serviceThreads.execute(new ServiceMessageTask(mpe));
//        }
//        else {
//            // Either slot 0 or 1 is free...
//            if (mpe.conflatedMessages[0] == null)
//                mpe.conflatedMessages[0] = publisherPayload;
//            else if (mpe.conflatedMessages[1] == null)
//                mpe.conflatedMessages[1] = publisherPayload;
//        }
        mpe.messages.offer(publisherPayload);
        if (mpe.getInProgressIndicator().compareAndSet(false, true)) {
            // A current thread is not inprogress so start one
            serviceThreads.execute(new ServiceMessageTask(mpe));
        }
    }

}



