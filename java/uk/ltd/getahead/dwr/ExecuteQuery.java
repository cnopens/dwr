package uk.ltd.getahead.dwr;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import uk.ltd.getahead.dwr.util.Log;

/**
 * This class represents a query made by a client in terms of the data that is
 * passed in to be converted to Java objects.
 * @author Joe Walker [joe at getahead dot ltd dot uk]
 */
public class ExecuteQuery
{
    /**
     * Simple ctor
     * @param req The users request
     * @param creatorManager The way we get an object to call methods on
     * @param converterManager The way we convert javascript to java
     */
    public ExecuteQuery(HttpServletRequest req, CreatorManager creatorManager, ConverterManager converterManager)
    {
        this.creatorManager = creatorManager;
        this.converterManager = converterManager;

        if (req.getParameter("id") != null)
        {
            parseGet(req);
        }
        else
        {
            parsePost(req);
        }

        parseParameters();

        Log.debug("Exec: " + toString());
    }

    /**
     * Parse an HTTP POST request to fill out the className, methodName and
     * paramList properties. This method should not fail unless it will not
     * be possible to return any sort of error to the user. Failure cases should
     * be handled by the <code>checkParams()</code> method.
     * @param req The incoming request
     */
    private void parsePost(HttpServletRequest req)
    {
        try
        {
            params = new HashMap();

            BufferedReader in = req.getReader();
            while (true)
            {
                String line = in.readLine();

                if (line == null)
                {
                    break;
                }

                int sep = line.indexOf(SEPARATOR);
                if (sep == -1)
                {
                    Log.warn("Missing separator in POST line: " + line);
                }
                else
                {
                    String key = line.substring(0, sep);
                    String value = line.substring(sep  + SEPARATOR.length());

                    params.put(key, value);
                }
            }
        }
        catch (IOException ex)
        {
            delayed = ex;
        }
    }

    /**
     * Parse an HTTP GET request to fill out the className, methodName and
     * paramList properties. This method should not fail unless it will not
     * be possible to return any sort of error to the user. Failure cases should
     * be handled by the <code>checkParams()</code> method.
     * @param req The incoming request
     */
    private void parseGet(HttpServletRequest req)
    {
        params = req.getParameterMap();
    }

    /**
     * Fish out the important parameters
     */
    private void parseParameters()
    {
        id = (String) params.get(KEY_ID);
        className = (String) params.get(KEY_CLASSNAME);
        methodName = (String) params.get(KEY_METHODNAME);
        xmlMode = Boolean.valueOf((String) params.get(KEY_XMLMODE)).booleanValue();

        paramList = new ArrayList();
        while (true)
        {
            String param = (String) params.get("param" + paramList.size());
            if (param == null || param.equals("undefined"))
            {
                break;
            }
            paramList.add(param);
        }
    }

    /**
     * Check (as far as we can) that the execute method will succeed.
     * @return The return from the method invocation
     * @throws ExecutionException If the method could not be executed
     * @throws ConversionException If conversion to java fails.
     * @throws IOException If there was an HTTP request parse failure
     */
    public Object execute() throws ExecutionException, ConversionException, IOException
    {
        if (delayed != null)
        {
            throw delayed;
        }

        if (className == null)
        {
            throw new IllegalArgumentException("Missing class parameter");
        }

        if (methodName == null)
        {
            throw new IllegalArgumentException("Missing method parameter");
        }

        // Get a list of the available matching methods with the coerced
        // parameters that we will use to call it if we choose to use that
        // method.
        Creator creator = creatorManager.getCreator(className);

        Method[] methods = creator.getType().getMethods();

        List available = new ArrayList();
        List coercedList = new ArrayList();
        methods:
        for (int i = 0; i < methods.length; i++)
        {
            // Check method name and access
            if (methods[i].getName().equals(methodName))
            {
                // Check number of parameters
                if (methods[i].getParameterTypes().length == paramList.size())
                {
                    Object[] coerced = new Object[paramList.size()];

                    // Check parameter types
                    params:
                    for (int j = 0; j < methods[i].getParameterTypes().length; j++)
                    {
                        Class paramType = methods[i].getParameterTypes()[j];
                        String param = (String) paramList.get(j);
                        coerced[j] = converterManager.convertTo(paramType, param);
                        if (coerced[j] == null)
                        {
                            break methods;
                        }
                    }

                    available.add(methods[i]);
                    coercedList.add(coerced);
                }
            }
        }

        // Pick a method to call
        if (available.size() > 1)
        {
            Log.warn("Warning multiple matching methods. Using first match.");
        }

        Method method = null;
        Object[] converted = null;

        // At the moment we are just going to take the first match, for a
        // later increment we might pack the best implementation
        if (!available.isEmpty())
        {
            method = (Method) available.get(0);
            converted = (Object[]) coercedList.get(0);
        }

        // Complain if there is nothing to call
        if (method == null)
        {
            throw new IllegalArgumentException("Missing method: " + toString());
        }

        try
        {
            // Create an instance
            Object object = creator.getInstance();

            // Execute
            Log.info("Executing: " + method.toString());
            return method.invoke(object, converted);
        }
        catch (InstantiationException ex)
        {
            throw new ExecutionException("Failed to create: " + creator.getClass().getName(), ex);
        }
        catch (IllegalArgumentException ex)
        {
            throw new ExecutionException("Illegal argument executing: " + method.getName(), ex);
        }
        catch (IllegalAccessException ex)
        {
            throw new ExecutionException("Illegal access executing: " + method.getName(), ex);
        }
        catch (InvocationTargetException ex)
        {
            throw new ExecutionException("Nested exception executing: " + method.getName(), ex.getTargetException());
        }
    }

    /**
     * @return Are we in XMLHttpRequest mode
     */
    public boolean isXmlMode()
    {
        return xmlMode;
    }

    /**
     * @return The id of this query
     */
    public String getId()
    {
        return id;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    public String toString()
    {
        StringBuffer allParams = new StringBuffer();
        for (Iterator it = paramList.iterator(); it.hasNext();)
        {
            allParams.append(it.next());
            if (it.hasNext())
            {
                allParams.append(", ");
            }
        }

        return "" + className + "." + methodName + "("+allParams+");";
    }

    private static final String SEPARATOR = "=";
    private static final String KEY_METHODNAME = "methodname";
    private static final String KEY_CLASSNAME = "classname";
    private static final String KEY_ID = "id";
    private static final String KEY_XMLMODE = "xml";

    private ConverterManager converterManager;
    private CreatorManager creatorManager;

    private boolean xmlMode;
    private String id;
    private String className;
    private String methodName;
    private IOException delayed;
    private Map params;
    private List paramList;
}
