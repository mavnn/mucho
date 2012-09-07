package nu.john.mucho;

import java.io.File;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.dom4j.Element;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.muc.ForbiddenException;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatManager;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.muc.NotAllowedException;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.PropertyEventDispatcher;
import org.jivesoftware.util.PropertyEventListener;
import org.jivesoftware.util.Log;
import org.xmpp.component.Component;
import org.xmpp.component.ComponentException;
import org.xmpp.component.ComponentManager;
import org.xmpp.component.ComponentManagerFactory;
import org.xmpp.muc.JoinRoom;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;
import org.xmpp.packet.Presence;

/**
 * MUC echo service plugin. It logs in to a MUC room and echoes  all 
 * chat sent to it to that room.  
 * 
 * @author John Harelius
 */
public class MuchoPlugin implements Plugin, Component, PropertyEventListener {
	
    private String m_serviceName;
    private ComponentManager m_componentManager;
    private PluginManager m_pluginManager;
    private MultiUserChatService m_mucservice;
    private MultiUserChatManager m_mucmanager;
    private String m_domain;
    private List<String> m_muchoIdList;
    private Hashtable<String, MUCRoom> m_muchoRooms;
    private String m_mucServiceName;
    private boolean m_debug;
    private boolean m_showSenderJid;
    private String m_conferenceService;
    

    /**
     * Constructs a new mucho plugin. Get properties.
     */
    public MuchoPlugin() {
        m_serviceName = JiveGlobals.getProperty("plugin.mucho.serviceName", "mucho");
        m_domain = JiveGlobals.getProperty("xmpp.domain", "john.nu");
        String debug = JiveGlobals.getProperty("plugin.mucho.debug", "false");
        m_debug = debug!=null && debug.equalsIgnoreCase("true");
        String showSenderJid = JiveGlobals.getProperty("plugin.mucho.showSenderJID", "false");
        m_showSenderJid = showSenderJid!=null && showSenderJid.equalsIgnoreCase("true");
        m_conferenceService = JiveGlobals.getProperty("plugin.mucho.conferenceService", "");
        
    }
    
    // Plugin Interface

    /**
     * Initialize stuff like setting variables and joining conferences.
     */
    public void initializePlugin(PluginManager manager, File pluginDirectory) {
        m_pluginManager = manager;

        
        // Register as a component.
        m_componentManager = ComponentManagerFactory.getComponentManager();
        try {
            m_componentManager.addComponent(m_serviceName, this);
        }
        catch (Exception e) {
            Log.error("Mucho Component Registration Error", e);
        }
        PropertyEventDispatcher.addListener(this);
        
        m_mucmanager = XMPPServer.getInstance().getMultiUserChatManager();
	m_mucServiceName = getMucServiceName();
    	m_mucservice = m_mucmanager.getMultiUserChatService(m_mucServiceName);
        
    	String prop = JiveGlobals.getProperty("plugin.mucho.ids", "");
        m_muchoIdList = stringToList(prop);
        debug("Found "+m_muchoIdList.size()+" mucho ids from property plugin.mucho.ids = "+prop);

        m_muchoRooms = new Hashtable<String,MUCRoom>();
        
    	joinConferences();
    }
    
    private String getMucServiceName()
    {
    	/**
    	 * If m_conferenceService property is set, return this. Otherwise 
    	 * get available services and return the first one. If no
    	 * services is available, use a default value.
    	 */
    	if(m_conferenceService !=null && m_conferenceService.length()>0)
    	{
    		debug("Conference service subdomain was specified as a property: "+m_conferenceService);
    		return m_conferenceService;
    	}
    	else
    	{
	    	List<MultiUserChatService> services = m_mucmanager.getMultiUserChatServices();
	    	if(services.size()>0)
	    	{
	    		String s = services.get(0).getServiceName();
	    		debug("Conference service subdomain not specified. Using first available: "+s);
	    		return services.get(0).getServiceName();
	
	    	}
	    	else
	    	{
	    		Log.error("No MUC services available! Defaulting to \"conference\"");
	    		return "conference";
	    	}
    	}
    }
    
    /**
     * Reset all variables and leave conferences.
     */
    public void destroyPlugin() {
        PropertyEventDispatcher.removeListener(this);
        // Unregister component.
        if (m_componentManager != null) {
            try {
                m_componentManager.removeComponent(m_serviceName);
            }
            catch (Exception e) {
                Log.error("Mucho failed to remove component", e);
            }
        }
        m_componentManager = null;
        m_pluginManager = null;
        m_mucmanager = null;
        m_mucservice = null;
        leaveConferences();
        m_muchoRooms = null;
        m_muchoIdList = null;
    }

    public void initialize(JID jid, ComponentManager p_componentManager) {
    }

    /**
     * Joins conferences by creating room (if not exist), joinig and unlocking (if not unocked).
     *
     */
    private void joinConferences()
    {
    	Iterator<String> iter = m_muchoIdList.iterator();
    	while (iter.hasNext()) {
			String name = iter.next();
	    	/** Join an existing room or create a new one. */
			try 
			{
				//Create room and set this user as owner!
				MUCRoom room = m_mucservice.getChatRoom(name, new JID(name, m_serviceName+"."+m_domain, null));
				debug("Room "+name+" is now available!");
				m_muchoRooms.put(name, room);
				
				
				// Join room
				String nick = name+" (bot)";
				JoinRoom joinRoom = new JoinRoom(name+"@"+m_serviceName+"."+m_domain, name+"@"+m_mucServiceName+"."+m_domain+"/"+nick);
				m_componentManager.sendPacket(this, joinRoom);
				debug("Joined room "+name);
				room.unlock(room.getOccupant(nick));
				debug("Unlocked room "+name);
				
				debug("Locked status "+room.isLocked());
				
			} 
			catch (NotAllowedException ne) 
			{
				Log.error("Mucho not allowed exception", ne);
			}
	    	catch(ComponentException ce)
	    	{
	    		Log.error("Mucho component exception", ce);
	    	}
	    	catch(UserNotFoundException ue)
	    	{
	    		Log.error("Mucho user not found exception", ue);
	    	}
	    	catch(ForbiddenException fe)
	    	{
	    		Log.error("Mucho forbidden exception", fe);
	    	}
    	}
    }
    
    /**
     * Leave conferences.
     *
     */
    private void leaveConferences()
    {
    	Iterator<String> iter = m_muchoIdList.iterator();
    	while (iter.hasNext()) {
			String name = iter.next();
			MUCRoom room = m_muchoRooms.get(name);
	    	try
	    	{
	    		room.leaveRoom(room.getOccupant(name));
	    	}
	    	catch(UserNotFoundException ue)
	    	{
	    		Log.error("Mucho user not found exception", ue);
	    	}
    	}
    }

    public void start() {
    }

    public void shutdown() {
    	
    }
    /**
     * Catch all packests and handlig them.
     */
    public void processPacket(Packet packet) {
        if (packet instanceof Message) {
            // Respond to incoming messages
            Message message = (Message)packet;
           	processMessage(message);
        }
        else if (packet instanceof Presence) {
            // Respond to presence subscription request or presence probe
            Presence presence = (Presence) packet;
            processPresence(presence);
        }
        else if (packet instanceof IQ) {
            // Handle disco packets
            IQ iq = (IQ) packet;
            // Ignore IQs of type ERROR or RESULT
            if (IQ.Type.error == iq.getType() || IQ.Type.result == iq.getType()) {
                return;
            }
            processIQ(iq);
        }
    }
    
    /**
     * Process message packet. This is where the message is echoed to the conference if sent to user.
     * @param p_message The message.
     */
    private void processMessage(Message p_message) {
    	String body = p_message.getBody();
    	if(body==null || body.length()==0)
    	{
    		return;
    	}

    	/**
    	 * See if message sent from user and echo message to conference.
    	 */
    	JID from = p_message.getFrom();
    	JID to = p_message.getTo();
    	/**
    	 * Since this bot broadcasts to all joined, including
    	 * itself, I have to make sure it is not to the bot iteself.
    	 * Otherwhise eternal loop...
    	 */
    	if(from.equals(to)==false)
    	{
    		/**
    		 * Make sure message is not from conference before broadcasting to it...
    		 */
    		if ((m_mucServiceName+"."+m_domain).equals(from.getDomain())==false)
	    	{
	    		String name = to.getNode();
	    		debug("Broadcasting to with id = "+name);
	            try {
	            	MUCRoom room = m_muchoRooms.get(name);
	            	Message msg = null;
	            	if(m_showSenderJid)
	            	{
	            		debug("Showing sender JID...");
	            		/**
	            		 * Create a new empty message thus losing all 
	            		 * "extra" properties from the original message...
	            		 */
	            		msg = new Message();
	            		String senderBareJid = from.toBareJID();
	            		if(senderBareJid!=null && senderBareJid.length()>0)
	            		{
	            			msg.setBody("["+senderBareJid+"] "+p_message.getBody());
	            		}
	            		else
	            		{
	            			msg.setBody("[unknown] "+p_message.getBody());
	            		}
	            	}
	            	else
	            	{
	            		/**
	            		 * Just copy message. This ensures all message properties
	            		 * from the original message to be available that would
	            		 * otherwise be lost if creating a new (empty) message.
	            		 */
	            		msg = p_message.createCopy();
	            	}
	            		
	            	msg.setType(Message.Type.groupchat);
	            	msg.setFrom(to);
	            	msg.setTo(room.getJID());
	            	debug("Sending message to groupchat: "+msg.toXML());
	            	m_componentManager.sendPacket(this, msg);
	            	
	            }
	            catch (ComponentException ce) {
	                Log.error("Mucho component exception", ce);
	            }
			}
    		else // Packet comes from conference
    		{
				/**
				 * Look for command to control this bot and carry out command. 
				 */
				String command = "@sayto: ";
				if(body.startsWith(command))
				{
					debug("Receiving command: "+body);
					int pos = body.indexOf(" ", command.length());
					if(pos>0 && body.length()>(pos+1))
					{
						String jid_to = body.substring(command.length(),pos);
						debug("JID to send to: "+jid_to);
						String text = body.substring(pos+1, body.length());
						debug("Message: "+text);
	    				String jid_from = to.getNode()+"@"+m_serviceName+"."+m_domain;
	    				debug("JID to send from: "+jid_from);
						
	    				Message send = new Message();
	    				send.setTo(jid_to);
	    				send.setBody(text);
	    				send.setType(Message.Type.chat);
	    				send.setFrom(jid_from);
	    				try
	    				{
	    					debug("Sending packet: "+send.toString());
	    					m_componentManager.sendPacket(this, send);
	    					debug("Packet sent!");
	    				}
	    				catch(ComponentException ce)
	    				{
	    					Log.error("Mucho component exception", ce);
	    				}
					}
				}
    		}
    	}
    }
    
    private void debug(String p_message)
    {
    	if(m_debug)
    	{
    		Log.debug(p_message);
    	}
    }

    /**
     * Process subscriptions.
     * @param presence
     */
    private void processPresence(Presence presence) {
        try {
            if (Presence.Type.subscribe == presence.getType()) {
                // Accept all presence requests if user has permissions
                // Reply that the subscription request was approved or rejected
                Presence reply = new Presence();
                reply.setTo(presence.getFrom());
                reply.setFrom(presence.getTo());
                reply.setType(Presence.Type.subscribed);
                m_componentManager.sendPacket(this, reply);
            }
            else if (Presence.Type.unsubscribe == presence.getType()) {
                // Send confirmation of unsubscription
                Presence reply = new Presence();
                reply.setTo(presence.getFrom());
                reply.setFrom(presence.getTo());
                reply.setType(Presence.Type.unsubscribed);
                m_componentManager.sendPacket(this, reply);
            }
            else if (Presence.Type.probe == presence.getType()) {
                // Send that the service is available
                Presence reply = new Presence();
                reply.setTo(presence.getFrom());
                reply.setFrom(presence.getTo());
                m_componentManager.sendPacket(this, reply);
            }
        }
        catch (ComponentException e) {
            Log.error(e);
        }
    }

    /**
     * Process disco
     * @param iq
     */
    private void processIQ(IQ iq) {
        IQ reply = IQ.createResultIQ(iq);
        Element childElement = iq.getChildElement();
        String namespace = childElement.getNamespaceURI();
        Element childElementCopy = iq.getChildElement().createCopy();
        reply.setChildElement(childElementCopy);
        if ("http://jabber.org/protocol/disco#info".equals(namespace)) {
            if (iq.getTo().getNode() == null) {
                // Return service identity and features
                Element identity = childElementCopy.addElement("identity");
                identity.addAttribute("category", "component");
                identity.addAttribute("type", "generic");
                identity.addAttribute("name", "Broadcast service");
                childElementCopy.addElement("feature")
                        .addAttribute("var", "http://jabber.org/protocol/disco#info");
                childElementCopy.addElement("feature")
                        .addAttribute("var", "http://jabber.org/protocol/disco#items");
            }
            else {
                // Return identity and features of the "all" group
                Element identity = childElementCopy.addElement("identity");
                identity.addAttribute("category", "component");
                identity.addAttribute("type", "generic");
                identity.addAttribute("name", "Broadcast all connected users");
                childElementCopy.addElement("feature")
                        .addAttribute("var", "http://jabber.org/protocol/disco#info");
            }
        }
        else {
            // Answer an error since the server can't handle the requested namespace
            reply.setError(PacketError.Condition.service_unavailable);
        }
        try {
            m_componentManager.sendPacket(this, reply);
        }
        catch (Exception e) {
            Log.error(e);
        }
    }    

    // Component Interface

    /**
     * Get plugin name.
     */
    public String getName() {
        // Get the name from the plugin.xml file.
        return m_pluginManager.getName(this);
    }
    /**
     * Get plugin description.
     */
    public String getDescription() {
        // Get the description from the plugin.xml file.
        return m_pluginManager.getDescription(this);
    }

    // Other Methods

    /**
     * Returns the service name of this component, which is "mucho" by default.
     *
     * @return the service name of this component.
     */
    public String getServiceName() {
        return m_serviceName;
    }

    /**
     * Sets the service name of this component, which is "mucho" by default.
     *
     * @param serviceName the service name of this component.
     */
    public void setServiceName(String serviceName) {
        JiveGlobals.setProperty("plugin.mucho.serviceName", serviceName);
    }

    // PropertyEventListener Methods

    public void propertySet(String property, Map params) {
    	if (property.equals("plugin.mucho.serviceName")) {
            changeServiceName((String)params.get("value"));
        }
    }

    public void propertyDeleted(String property, Map params) {
    	if (property.equals("plugin.mucho.serviceName")) {
            changeServiceName("mucho");
        }
    }

    public void xmlPropertySet(String property, Map params) {
        // Ignore.
    }

    public void xmlPropertyDeleted(String property, Map params) {
        // Ignore.
    }

    /**
     * Changes the service name to a new value.
     *
     * @param serviceName the service name.
     */
    private void changeServiceName(String serviceName) {
         if (serviceName == null) {
            throw new NullPointerException("Service name cannot be null");
        }
        if (this.m_serviceName.equals(serviceName)) {
            return;
        }

        // Re-register the service.
        try {
            m_componentManager.removeComponent(this.m_serviceName);
        }
        catch (Exception e) {
            Log.error(e);
        }
        try {
            m_componentManager.addComponent(serviceName, this);
        }
        catch (Exception e) {
            Log.error(e);
        }
        this.m_serviceName = serviceName;
    }

    /**
     * Returns a comma-delimitted list of strings into a Collection of Strings.
     *
     * @param p_string the String.
     * @return a list.
     */
    private List<String> stringToList(String p_string) 
    {
        List<String> values = new ArrayList<String>();
        StringTokenizer tokens = new StringTokenizer(p_string, ",");
        while (tokens.hasMoreTokens()) {
            String value = tokens.nextToken().trim();
            if (value.length()>0) {
                values.add(value);
            }
        }
        return values;
    }
}
