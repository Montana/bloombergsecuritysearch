/*
 * Copyright 2012. Bloomberg Finance L.P.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to
 * deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:  The above
 * copyright notice and this permission notice shall be included in all copies
 * or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */
package com.bloomberglp.blpapi.securitysearch;

import com.bloomberglp.blpapi.CorrelationID;
import com.bloomberglp.blpapi.Element;
import com.bloomberglp.blpapi.Event;
import com.bloomberglp.blpapi.EventQueue;
import com.bloomberglp.blpapi.Identity;
import com.bloomberglp.blpapi.InvalidConversionException;
import com.bloomberglp.blpapi.Message;
import com.bloomberglp.blpapi.MessageIterator;
import com.bloomberglp.blpapi.Name;
import com.bloomberglp.blpapi.NotFoundException;
import com.bloomberglp.blpapi.Request;
import com.bloomberglp.blpapi.Service;
import com.bloomberglp.blpapi.Session;
import com.bloomberglp.blpapi.SessionOptions;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map.Entry;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class SecuritySearchApiConnection extends Thread
{
    private static final Name AUTHORIZATION_SUCCESS = Name.getName("AuthorizationSuccess");
    private static final Name TOKEN_SUCCESS = Name.getName("TokenGenerationSuccess");
    private static final Name SESSION_TERMINATED = Name.getName("SessionTerminated");
    private static final Name SESSION_FAILURE = Name.getName("SessionStartupFailure");
    private static final Name TOKEN_ELEMENT = Name.getName("token");
    private static final Name DESCRIPTION_ELEMENT = Name.getName("description");
    private static final Name QUERY_ELEMENT = Name.getName("query");
    private static final Name RESULTS_ELEMENT = Name.getName("results");
    private static final Name MAX_RESULTS_ELEMENT = Name.getName("maxResults");

    private static final Name SECURITY_ELEMENT = Name.getName("security");

    private static final Name ERROR_RESPONSE = Name.getName("ErrorResponse");
    private static final Name INSTRUMENT_LIST_RESPONSE = Name.getName("InstrumentListResponse");

    private static final Name INSTRUMENT_LIST_REQUEST = Name.getName("instrumentListRequest");

    private static final String INSTRUMENT_SERVICE = "//blp/instruments";
    private static final String AUTH_SERVICE = "//blp/apiauth";
    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 8194;
    private static final String DEFAULT_QUERY_STRING = "IBM";
    private static final String DEFAULT_YK_FILTER_STRING = "";
    private static final int DEFAULT_MAX_RESULTS = 10;
    private static final int WAIT_TIME_MS = 10 * 1000; // 10 seconds

    private static final String[] FILTERS_INSTRUMENTS = {
        "yellowKeyFilter",
        "languageOverride"
    };

    private String d_queryString = DEFAULT_QUERY_STRING;
    private String d_ykFilter = DEFAULT_YK_FILTER_STRING;
    private String d_host = DEFAULT_HOST;
    private Name d_requestType = INSTRUMENT_LIST_REQUEST;
    private int d_port = DEFAULT_PORT;
    private int d_maxResults = DEFAULT_MAX_RESULTS;
    private String d_authOptions = null;
    private HashMap<String, String> d_filters = new HashMap<String, String>();
    private JSONObject d_result = new JSONObject();
    private Writer d_out;
    private Reader d_in;

    private Socket d_clientConnection = null;

    // Authorize should be called before any requests are sent.
    public static void authorize(Identity identity, Session session) throws Exception {
        if (!session.openService(AUTH_SERVICE)) {
            throw new Exception(
                    String.format("Failed to open auth service: %1$s",
                                  AUTH_SERVICE));
        }
        Service authService = session.getService(AUTH_SERVICE);

        EventQueue tokenEventQueue = new EventQueue();
        session.generateToken(new CorrelationID(tokenEventQueue), tokenEventQueue);
        String token = null;
        // Generate token responses will come on the dedicated queue. There would be no other
        // messages on that queue.
        Event event = tokenEventQueue.nextEvent(WAIT_TIME_MS);

        if (event.eventType() == Event.EventType.TOKEN_STATUS
                || event.eventType() == Event.EventType.REQUEST_STATUS) {
            for (Message msg: event) {
                System.out.println(msg);
                if (msg.messageType() == TOKEN_SUCCESS) {
                    token = msg.getElementAsString(TOKEN_ELEMENT);
                }
            }
        }
        if (token == null) {
            throw new Exception("Failed to get token");
        }

        Request authRequest = authService.createAuthorizationRequest();
        authRequest.set(TOKEN_ELEMENT, token);

        session.sendAuthorizationRequest(authRequest, identity, null);

        long waitDuration = WAIT_TIME_MS;
        for (long startTime = System.currentTimeMillis();
                waitDuration > 0;
                waitDuration -= (System.currentTimeMillis() - startTime)) {
            event = session.nextEvent(waitDuration);
            // Since no other requests were sent using the session queue, the response can
            // only be for the Authorization request
            if (event.eventType() != Event.EventType.RESPONSE
                    && event.eventType() != Event.EventType.PARTIAL_RESPONSE
                    && event.eventType() != Event.EventType.REQUEST_STATUS) {
                continue;
            }

            for (Message msg: event) {
                System.out.println(msg);
                if (msg.messageType() != AUTHORIZATION_SUCCESS) {
                    throw new Exception("Authorization Failed");
                }
            }
            return;
        }
        throw new Exception("Authorization Failed");
    }

    @SuppressWarnings("unchecked")
	private void processInstrumentListResponse(Message msg) {
        Element results = msg.getElement(RESULTS_ELEMENT);
        int numResults = results.numValues();
        System.out.println("Processing " + numResults + " results:");
        JSONArray arr = new JSONArray(); 
        d_result.put("result", arr);
        for (int i = 0; i < numResults; ++i) {
            Element result = results.getValueAsElement(i);
            JSONObject el = new JSONObject();
            el.put("security", result.getElementAsString(SECURITY_ELEMENT));
            el.put("description", result.getElementAsString(DESCRIPTION_ELEMENT));
            arr.add(el);
        }
    }

    private void processResponseEvent(Event event) {
        MessageIterator msgIter = event.messageIterator();
        while (msgIter.hasNext()) {
            Message msg = msgIter.next();
            if (msg.messageType() == ERROR_RESPONSE) {
                String description = msg.getElementAsString(DESCRIPTION_ELEMENT);
                System.out.println("Received error: " + description);
            }
            else if (msg.messageType() == INSTRUMENT_LIST_RESPONSE) {
                processInstrumentListResponse(msg);
            }
            else {
                System.err.println("Unknown MessageType received");
            }
        }
    }

    @SuppressWarnings("unchecked")
	private void eventLoop(Session session) throws InterruptedException {
        boolean done = false;
        while (!done) {
            Event event = session.nextEvent();
            if (event.eventType() == Event.EventType.PARTIAL_RESPONSE) {
                System.out.println("Processing Partial Response");
                processResponseEvent(event);
            }
            else if (event.eventType() == Event.EventType.RESPONSE) {
                System.out.println("Processing Response");
                processResponseEvent(event);
                done = true;
            }
            else {
                MessageIterator msgIter = event.messageIterator();
                while (msgIter.hasNext()) {
                    Message msg = msgIter.next();
                    System.out.println(msg.asElement());
                    if (event.eventType() == Event.EventType.SESSION_STATUS) {
                        if (msg.messageType() == SESSION_TERMINATED) {
                        	done = true;
                            d_result.put("Error", "SESSION TERMINATED");
                        }
                        if (msg.messageType() == SESSION_FAILURE) {
                            done = true;
                            d_result.put("Error", "SESSION FAILURE");
                        }
                    }
                }
            }
        }
    }

    private void sendRequest(Session session, Identity identity) throws Exception {
        System.out.println("Sending Request: " + d_requestType.toString());
        Service instrumentService = session.getService(INSTRUMENT_SERVICE);
        Request request;
        try {
            request = instrumentService.createRequest(d_requestType.toString());
        }
        catch (NotFoundException e) {
            throw new Exception(
                    String.format("Request type not found: %1$s", d_requestType),
                    e);
        }
        System.out.println("Sending Request:21 " + d_requestType.toString());
        request.set(QUERY_ELEMENT, d_queryString);
        if(!d_ykFilter.isEmpty()) {
        	request.set(FILTERS_INSTRUMENTS[0], d_ykFilter);
        }
        request.set(MAX_RESULTS_ELEMENT, d_maxResults);
        System.out.println("Sending Request:3 " + d_requestType.toString());
        for (Entry<String, String> entry: d_filters.entrySet()) {
            try {
            	System.out.println("Sending Request:3 " + entry.getKey());
                request.set(entry.getKey(), entry.getValue());
            }
            catch (NotFoundException e) {
                throw new Exception(String.format("Filter not found: %1$s", entry.getKey()), e);
            }
            catch (InvalidConversionException e) {
                throw new Exception(
                        String.format(
                        "Invalid value: %1$s for filter: %2$s",
                        entry.getValue(),
                        entry.getKey()),
                        e);
            }
        }

        System.out.println(request);
        session.sendRequest(request, identity, null);
    }

    private static void stopSession(Session session) {
        if (session != null) {
            boolean done = false;
            while (!done) {
                try {
                    session.stop();
                    done = true;
                }
                catch (InterruptedException e) {
                    System.out.println("InterrupedException caught (ignoring)");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
	private void returnResponse() throws IOException {
    	if (d_result.isEmpty()) {
    		d_result.put("error", "Unknown error");
    	}
    	
    	d_out.write(d_result.toJSONString());
    	d_out.flush();
    	System.out.println("wrote" + d_result.toJSONString());
    }
    
    public void processRequest() {
        Session session = null;
        try {
            //parseCommandLine(args);
            SessionOptions sessionOptions = new SessionOptions();
            sessionOptions.setServerHost(d_host);
            sessionOptions.setServerPort(d_port);
            sessionOptions.setAuthenticationOptions(d_authOptions);

            System.out.println("Connecting to " + d_host + ":" + d_port);
            session = new Session(sessionOptions);
            if (!session.start()) {
                System.err.println("Failed to start session.");
                return;
            }

            Identity identity = session.createIdentity();
            if (d_authOptions != null) {
                authorize(identity, session);
            }

            if (!session.openService(INSTRUMENT_SERVICE)) {
                System.err.println("Failed to open " + INSTRUMENT_SERVICE);
                return;
            }

            sendRequest(session, identity);
            eventLoop(session);
        }
        catch (Exception e) {
            System.err.printf("Exception: %1$s\n", e.getMessage());
            System.err.println();
            //printUsage();
        }
        finally {
            if (session != null) {
                stopSession(session);
            }
        }
    }
    
    private void parseRequest(JSONObject request)
    {
    	System.out.println("req" + request.toJSONString());
    	if(!request.containsKey("request"))
    	{
    		//spit error
    		return;
    	}
    	
    	JSONObject params = (JSONObject) request.get("request");

    	if(params.containsKey("query_string")) {
    		String queryString = (String) params.get("query_string");
    		if(!queryString.isEmpty()) {
    			d_queryString = queryString;
    		}
    	}
    	
    	if(params.containsKey("max_results")) {
    		long maxResults = (long) params.get("max_results");
    		if(maxResults > 0 && maxResults < 65535) {
    			d_maxResults = (int) maxResults;
    		}
    	}
    	
    	if(params.containsKey("yk_filter")) {
    		String ykFilter = (String) params.get("yk_filter");
    		if(!ykFilter.isEmpty()) {
    			d_ykFilter = ykFilter;
    		}
    	}
    	
    	System.out.println("Params: " + d_queryString);
    }

    public void run() {
    	System.out.println("New connection started");
    	try {
    		while(true) {
    			System.out.println("Waiting");
    			int c = d_in.read();
    			System.out.println("Read " + c);
    			char ch = (char)c;
    			if(c == -1) {
    				return;
    			}
    			
    			if(ch != '{') {
    				//if the first char isn't { we ignore it
    				continue;
    			}
    			
    			int brackets = 1;
    			StringBuffer sb = new StringBuffer();
    			sb.append(ch);
    			while(brackets > 0) {

    				c = d_in.read();
    				ch = (char)c;

    				if(c == -1) {
    					return;
    				}
    				if(ch == '}') {
    					--brackets;
    				}
    				if(ch == '{') {
    					++brackets;
    				}

    				sb.append(ch);
    			}

    			//we got a JSON object
    			JSONParser parser = new JSONParser();
    			JSONObject jobj;
    			try {
    				jobj = (JSONObject) parser.parse(sb.toString());
    			} catch (ParseException e) {
    				d_in.close();
    				d_out.close();
    				d_clientConnection.close();
    				e.printStackTrace();
    				return;
    			}
    			System.out.println(jobj.toJSONString());
    			parseRequest(jobj);
    			processRequest();
    			returnResponse();
    			d_in.close();
    			d_out.close();
    			d_clientConnection.close();
    			return;

    		}
    	} catch (IOException e) {
    		// TODO Auto-generated catch block
    		e.printStackTrace();
    	} catch (Exception e) {
    		e.printStackTrace();
    	}
    }
    
    public SecuritySearchApiConnection(Socket clientSocket, String host, int port) {
    	d_host = host;
    	d_port = port;
    	d_clientConnection = clientSocket;
    	if(clientSocket == null) {
    		return;
    	}
    	try {
			d_out = new BufferedWriter(
					new OutputStreamWriter(d_clientConnection.getOutputStream(), "UTF-8"));
			d_in = new BufferedReader(
					new InputStreamReader(d_clientConnection.getInputStream(), "UTF-8"));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }

    public static void main(String[] args) {
        System.out.println("SecurityLookupExample");
        SecuritySearchApiConnection example = new SecuritySearchApiConnection(null, "10.8.8.1", DEFAULT_PORT);
        example.processRequest();
    }
}