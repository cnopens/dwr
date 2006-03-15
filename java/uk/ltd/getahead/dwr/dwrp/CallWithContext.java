/*
 * Copyright 2005 Joe Walker
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
package uk.ltd.getahead.dwr.dwrp;

import uk.ltd.getahead.dwr.Call;

/**
 * Call is a POJO to encapsulate the information required to make a single java
 * call, including the result of the call (either returned data or exception).
 * @author Joe Walker [joe at getahead dot ltd dot uk]
 */
public class CallWithContext extends Call
{
    /**
     * @param inctx The inctx to set.
     */
    public void setInboundContext(InboundContext inctx)
    {
        this.inctx = inctx;
    }

    /**
     * @return Returns the inctx.
     */
    public InboundContext getInboundContext()
    {
        return inctx;
    }

    /**
     * @param reply The reply to set.
     */
    public void setReply(OutboundVariable reply)
    {
        this.reply = reply;
    }

    /**
     * @return Returns the reply.
     */
    public OutboundVariable getReply()
    {
        return reply;
    }

    /**
     * @param th The th to set.
     */
    public void setThrowable(OutboundVariable th)
    {
        this.th = th;
    }

    /**
     * @return Returns the th.
     */
    public OutboundVariable getThrowable()
    {
        return th;
    }

    private InboundContext inctx = new InboundContext();

    private OutboundVariable reply =  null;

    private OutboundVariable th = null;
}