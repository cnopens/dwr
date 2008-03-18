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
package org.directwebremoting.jsonp;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.directwebremoting.ScriptBuffer;
import org.directwebremoting.dwrp.ProtocolConstants;
import org.directwebremoting.extend.AccessControl;
import org.directwebremoting.extend.Call;
import org.directwebremoting.extend.Calls;
import org.directwebremoting.extend.ConverterManager;
import org.directwebremoting.extend.Creator;
import org.directwebremoting.extend.CreatorManager;
import org.directwebremoting.extend.Handler;
import org.directwebremoting.extend.InboundContext;
import org.directwebremoting.extend.InboundVariable;
import org.directwebremoting.extend.MarshallException;
import org.directwebremoting.extend.Remoter;
import org.directwebremoting.extend.Replies;
import org.directwebremoting.extend.Reply;
import org.directwebremoting.extend.ScriptBufferUtil;
import org.directwebremoting.extend.TypeHintContext;
import org.directwebremoting.util.MimeConstants;

/**
 * A Handler JSON/REST DWR calls
 * @author Joe Walker [joe at getahead dot ltd dot uk]
 */
public class JsonCallHandler implements Handler
{
    /* (non-Javadoc)
     * @see org.directwebremoting.Handler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        if (!jsonEnabled)
        {
            log.warn("JSON request denied. To enable JSON mode add an init-param of jsonEnabled=true to web.xml");
            throw new SecurityException("JSON interface disabled");
        }

        // Get the output stream and setup the mime type
        response.setContentType(MimeConstants.MIME_JS);
        PrintWriter out = response.getWriter();

        try
        {
            Calls calls = convertToCalls(request);
            Replies replies = remoter.execute(calls);

            // There will only be one of these while JSON mode does not do batching
            for (int i = 0; i < replies.getReplyCount(); i++)
            {
                Reply reply = replies.getReply(i);
            
                try
                {
                    // The existence of a throwable indicates that something went wrong
                    if (reply.getThrowable() != null)
                    {
                        Throwable ex = reply.getThrowable();
                        writeData(out, ex);
            
                        log.warn("--Erroring: message[" + ex.toString() + ']');
                    }
                    else
                    {
                        Object data = reply.getReply();
                        writeData(out, data);
                    }
                }
                catch (Exception ex)
                {
                    // This is a bit of a "this can't happen" case so I am a bit
                    // nervous about sending the exception to the client, but we
                    // want to avoid silently dying so we need to do something.
                    writeData(out, ex);
                    log.error("--MarshallException: message=" + ex.toString());
                }
            }
        }
        catch (Exception ex)
        {
            writeData(out, ex);
        }
    }

    /**
     * Take an HttpServletRequest and create from it a Calls object.
     * @param request The input data
     * @return A Calls object that represents the data in the request
     */
    @SuppressWarnings("unchecked")
    public Calls convertToCalls(HttpServletRequest request)
    {
        // JSON does not support batching
        Calls calls = new Calls();

        Call call = new Call();
        calls.addCall(call);

        String pathInfo = request.getPathInfo();
        String[] pathParts = pathInfo.split("/");

        if (pathParts.length < 4)
        {
            log.warn("pathInfo '" + pathInfo + "' contains " + pathParts.length + " parts. At least 4 are required.");
            throw new JsonCallException("Bad JSON request. See logs for more details.");
        }

        InboundContext inboundContext = new InboundContext();
        call.setScriptName(pathParts[2]);
        call.setMethodName(pathParts[3]);

        if (pathParts.length > 4)
        {
            for (int i = 4; i < pathParts.length; i++)
            {
                String key = ProtocolConstants.INBOUND_CALLNUM_PREFIX + 0 +
                             ProtocolConstants.INBOUND_CALLNUM_SUFFIX +
                             ProtocolConstants.INBOUND_KEY_PARAM + (i - 4);
                inboundContext.createInboundVariable(0, key, "string", pathParts[i]);
            }
        }
        else
        {
            Map<String, String[]> requestParams = request.getParameterMap();
            int i = 0;
            while (true)
            {
                String[] values = requestParams.get("param" + i);
                if (values == null)
                {
                    break;
                }
                else
                {
                    String key = ProtocolConstants.INBOUND_CALLNUM_PREFIX + 0 +
                                 ProtocolConstants.INBOUND_CALLNUM_SUFFIX +
                                 ProtocolConstants.INBOUND_KEY_PARAM + i;
                    inboundContext.createInboundVariable(0, key, "string", values[0]);
                    i++;
                }
            }
        }

        // Which method are we using?
        call.findMethod(creatorManager, converterManager, inboundContext);
        Method method = call.getMethod();

        // Check this method is accessible
        Creator creator = creatorManager.getCreator(call.getScriptName(), true);
        accessControl.assertExecutionIsPossible(creator, call.getScriptName(), method);

        // We are now sure we have the set of input lined up. They may
        // cross-reference so we do the de-referencing all in one go.
        try
        {
            inboundContext.dereference();
        }
        catch (MarshallException ex)
        {
            log.warn("Dereferencing exception", ex);
            throw new JsonCallException("Error dereferencing call. See logs for more details.");
        }

        // Convert all the parameters to the correct types
        Object[] params = new Object[method.getParameterTypes().length];
        for (int j = 0; j < method.getParameterTypes().length; j++)
        {
            Class<?> paramType = method.getParameterTypes()[j];
            InboundVariable param = inboundContext.getParameter(0, j);
            TypeHintContext incc = new TypeHintContext(converterManager, method, j);

            try
            {
                params[j] = converterManager.convertInbound(paramType, param, inboundContext, incc);
            }
            catch (MarshallException ex)
            {
                log.warn("Marshalling exception. Param " + j + ", ", ex);
                throw new JsonCallException("Error marshalling parameters. See logs for more details.");
            }
        }

        call.setParameters(params);

        return calls;
    }

    /**
     * Create output for some data and write it to the given stream.
     */
    public void writeData(PrintWriter out, Object data)
    {
        try
        {
            ScriptBuffer buffer = new ScriptBuffer();
            buffer.appendData(data);

            String output = ScriptBufferUtil.createOutput(buffer, converterManager, true);
            out.println(output);
        }
        catch (MarshallException ex)
        {
            log.warn("--MarshallException: class=" + ex.getConversionType().getName(), ex);

            ScriptBuffer buffer = new ScriptBuffer();
            buffer.appendData(ex);

            try
            {
                String output = ScriptBufferUtil.createOutput(buffer, converterManager, true);
                out.println(output);
            }
            catch (MarshallException ex1)
            {
                log.error("--Nested MarshallException: Is there an exception handler registered? class=" + ex.getConversionType().getName(), ex);
            }
        }
    }

    /* (non-Javadoc)
     * @see org.directwebremoting.Marshaller#isConvertable(java.lang.Class)
     */
    public boolean isConvertable(Class<?> paramType)
    {
        return converterManager.isConvertable(paramType);
    }

    /**
     * Accessor for the DefaultCreatorManager that we configure
     * @param converterManager The new DefaultConverterManager
     */
    public void setConverterManager(ConverterManager converterManager)
    {
        this.converterManager = converterManager;
    }

    /**
     * Accessor for the DefaultCreatorManager that we configure
     * @param creatorManager The new DefaultConverterManager
     */
    public void setCreatorManager(CreatorManager creatorManager)
    {
        this.creatorManager = creatorManager;
    }

    /**
     * Accessor for the security manager
     * @param accessControl The accessControl to set.
     */
    public void setAccessControl(AccessControl accessControl)
    {
        this.accessControl = accessControl;
    }

    /**
     * Are we allowing remote hosts to contact us using JSON?
     */
    public void setJsonEnabled(boolean jsonEnabled)
    {
        this.jsonEnabled = jsonEnabled;
    }

    /**
     * Setter for the remoter
     * @param remoter The new remoter
     */
    public void setRemoter(Remoter remoter)
    {
        this.remoter = remoter;
    }

    /**
     * Are we allowing remote hosts to contact us using JSON?
     */
    protected boolean jsonEnabled = false;

    /**
     * The bean to execute remote requests and generate interfaces
     */
    protected Remoter remoter = null;

    /**
     * How we convert parameters
     */
    protected ConverterManager converterManager = null;

    /**
     * How we create new beans
     */
    protected CreatorManager creatorManager = null;

    /**
     * The security manager
     */
    protected AccessControl accessControl = null;

    /**
     * The log stream
     */
    private static final Log log = LogFactory.getLog(JsonCallHandler.class);
}