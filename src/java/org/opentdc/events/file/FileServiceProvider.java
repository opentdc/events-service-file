/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Arbalo AG
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.opentdc.events.file;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import javax.servlet.ServletContext;

import org.opentdc.file.AbstractFileServiceProvider;
import org.opentdc.events.EventModel;
import org.opentdc.events.InvitationState;
import org.opentdc.events.SalutationType;
import org.opentdc.events.ServiceProvider;
import org.opentdc.service.exception.DuplicateException;
import org.opentdc.service.exception.InternalServerErrorException;
import org.opentdc.service.exception.NotFoundException;
import org.opentdc.service.exception.ValidationException;
import org.opentdc.util.EmailSender;
import org.opentdc.util.FreeMarkerConfig;
import org.opentdc.util.PrettyPrinter;

import freemarker.template.Template;

/**
 * A file-based or transient implementation of the Events service.
 * @author Bruno Kaiser
 *
 */
public class FileServiceProvider extends AbstractFileServiceProvider<EventModel> implements ServiceProvider {
	
	private static Map<String, EventModel> index = null;
	private static final Logger logger = Logger.getLogger(FileServiceProvider.class.getName());
	private EmailSender emailSender = null;
	private static final String SUBJECT = "Einladung zum Arbalo Launch Event";

	/**
	 * Constructor.
	 * @param context the servlet context.
	 * @param prefix the simple class name of the service provider
	 * @throws IOException
	 */
	public FileServiceProvider(
		ServletContext context, 
		String prefix
	) throws IOException {
		super(context, prefix);
		if (index == null) {
			index = new HashMap<String, EventModel>();
			List<EventModel> _events = importJson();
			for (EventModel _event : _events) {
				index.put(_event.getId(), _event);
			}
			new FreeMarkerConfig(context);
			emailSender = new EmailSender(context);
			logger.info(_events.size() + " Events imported.");
		}
	}

	/* (non-Javadoc)
	 * @see org.opentdc.events.ServiceProvider#list(java.lang.String, java.lang.String, long, long)
	 */
	@Override
	public ArrayList<EventModel> list(
		String queryType,
		String query,
		int position,
		int size
	) {
		ArrayList<EventModel> _events = new ArrayList<EventModel>(index.values());
		Collections.sort(_events, EventModel.EventComparator);
		ArrayList<EventModel> _selection = new ArrayList<EventModel>();
		for (int i = 0; i < _events.size(); i++) {
			if (i >= position && i < (position + size)) {
				_selection.add(_events.get(i));
			}			
		}
		logger.info("list(<" + query + ">, <" + queryType + 
				">, <" + position + ">, <" + size + ">) -> " + _selection.size() + " events.");
		return _selection;
	}

	/* (non-Javadoc)
	 * @see org.opentdc.events.ServiceProvider#create(org.opentdc.events.EventsModel)
	 */
	@Override
	public EventModel create(
		EventModel event) 
	throws DuplicateException, ValidationException {
		logger.info("create(" + PrettyPrinter.prettyPrintAsJSON(event) + ")");
		String _id = event.getId();
		if (_id == null || _id == "") {
			_id = UUID.randomUUID().toString();
		} else {
			if (index.get(_id) != null) {
				// object with same ID exists already
				throw new DuplicateException("event <" + _id + "> exists already.");
			}
			else { 	// a new ID was set on the client; we do not allow this
				throw new ValidationException("event <" + _id + 
					"> contains an ID generated on the client. This is not allowed.");
			}
		}
		// enforce mandatory fields
		if (event.getFirstName() == null || event.getFirstName().length() == 0) {
			throw new ValidationException("event <" + _id + 
					"> must contain a valid firstName.");
		}
		if (event.getLastName() == null || event.getLastName().length() == 0) {
			throw new ValidationException("event <" + _id + 
					"> must contain a valid lastName.");
		}
		if (event.getEmail() == null || event.getEmail().length() == 0) {
			throw new ValidationException("event <" + _id + 
					"> must contain a valid email address.");
		}
		if (event.getInvitationState() == null) {
			event.setInvitationState(InvitationState.INITIAL);
		}
		if (event.getSalutation() == null) {
			event.setSalutation(SalutationType.DU_M);
		}
		event.setId(_id);
		Date _date = new Date();
		event.setCreatedAt(_date);
		event.setCreatedBy(getPrincipal());
		event.setModifiedAt(_date);
		event.setModifiedBy(getPrincipal());
		index.put(_id, event);
		logger.info("create(" + PrettyPrinter.prettyPrintAsJSON(event) + ")");
		if (isPersistent) {
			exportJson(index.values());
		}
		return event;
	}

	/* (non-Javadoc)
	 * @see org.opentdc.events.ServiceProvider#read(java.lang.String)
	 */
	@Override
	public EventModel read(
		String id) 
	throws NotFoundException {
		EventModel _event = index.get(id);
		if (_event == null) {
			throw new NotFoundException("no event with ID <" + id
					+ "> was found.");
		}
		logger.info("read(" + id + ") -> " + PrettyPrinter.prettyPrintAsJSON(_event));
		return _event;
	}

	/* (non-Javadoc)
	 * @see org.opentdc.events.ServiceProvider#update(java.lang.String, org.opentdc.events.EventsModel)
	 */
	@Override
	public EventModel update(
		String id, 
		EventModel event
	) throws NotFoundException, ValidationException {
		EventModel _event = index.get(id);
		if(_event == null) {
			throw new NotFoundException("no event with ID <" + id
					+ "> was found.");
		} 
		if (! _event.getCreatedAt().equals(event.getCreatedAt())) {
			logger.warning("event <" + id + ">: ignoring createdAt value <" + event.getCreatedAt().toString() + 
					"> because it was set on the client.");
		}
		if (! _event.getCreatedBy().equalsIgnoreCase(event.getCreatedBy())) {
			logger.warning("event <" + id + ">: ignoring createdBy value <" + event.getCreatedBy() +
					"> because it was set on the client.");
		}
		if (event.getFirstName() == null || event.getFirstName().length() == 0) {
			throw new ValidationException("event <" + id + 
					"> must contain a valid firstName.");
		}
		if (event.getLastName() == null || event.getLastName().length() == 0) {
			throw new ValidationException("event <" + id + 
					"> must contain a valid lastName.");
		}
		if (event.getEmail() == null || event.getEmail().length() == 0) {
			throw new ValidationException("event <" + id + 
					"> must contain a valid email address.");
		}
		if (event.getInvitationState() == null) {
			event.setInvitationState(InvitationState.INITIAL);
		}
		if (event.getSalutation() == null) {
			event.setSalutation(SalutationType.DU_M);
		}
		_event.setFirstName(event.getFirstName());
		_event.setLastName(event.getLastName());
		_event.setEmail(event.getEmail());
		_event.setSalutation(event.getSalutation());
		_event.setInvitationState(event.getInvitationState());
		_event.setComment(event.getComment());
		_event.setInternalComment(event.getInternalComment());
		_event.setModifiedAt(new Date());
		_event.setModifiedBy(getPrincipal());
		index.put(id, _event);
		logger.info("update(" + id + ") -> " + PrettyPrinter.prettyPrintAsJSON(_event));
		if (isPersistent) {
			exportJson(index.values());
		}
		return _event;
	}

	/* (non-Javadoc)
	 * @see org.opentdc.events.ServiceProvider#delete(java.lang.String)
	 */
	@Override
	public void delete(
		String id) 
	throws NotFoundException, InternalServerErrorException {
		EventModel _event = index.get(id);
		if (_event == null) {
			throw new NotFoundException("event <" + id
					+ "> was not found.");
		}
		if (index.remove(id) == null) {
			throw new InternalServerErrorException("event <" + id
					+ "> can not be removed, because it does not exist in the index");
		}
		logger.info("delete(" + id + ")");
		if (isPersistent) {
			exportJson(index.values());
		}
	}
	
	/**
	 * Retrieve the email address of the contact.
	 * @param contactName the name of the contact
	 * @return the corresponding email address
	 */
	private String getEmailAddress(String contactName) {
		logger.info("getEmailAddress(" + contactName + ")");
		String _emailAddress = null;
		if (contactName == null || contactName.isEmpty()) {
			contactName = "arbalo";
		}
	       if (contactName.equalsIgnoreCase("bruno")) {
	        	_emailAddress = "bruno.kaiser@arbalo.ch";
	        } else if (contactName.equalsIgnoreCase("thomas")) {
	        	_emailAddress = "thomas.huber@arbalo.ch";
	        } else if (contactName.equalsIgnoreCase("peter")) {
	        	_emailAddress = "peter.windemann@arbalo.ch";
	        } else if (contactName.equalsIgnoreCase("marc")) {
	        	_emailAddress = "marc.hofer@arbalo.ch";
	        } else if (contactName.equalsIgnoreCase("werner")) {
	        	_emailAddress = "werner.froidevaux@arbalo.ch";        	
	        } else {
	        	_emailAddress = "info@arbalo.ch";        	        	
	        }
	        logger.info("getEmailAddress(" + contactName + ") -> " + _emailAddress);
	        return _emailAddress;	
	}
	
	/**
	 * @param salutation
	 * @return
	 */
	private Template getTemplate(
			SalutationType salutation, String contactName) {
		String _templateName = null;
		if (contactName == null || contactName.isEmpty()) {
			contactName = "arbalo";
		}
		switch (salutation) {
		case HERR: _templateName = "emailHerr_" + contactName + ".ftl"; break;
		case FRAU: _templateName = "emailFrau_" + contactName + ".ftl"; break;
		case DU_F: _templateName = "emailDuf_" + contactName + ".ftl";  break;
		case DU_M: _templateName = "emailDum_" + contactName + ".ftl";  break;
		}
		return FreeMarkerConfig.getTemplateByName(_templateName);
	}


	/* (non-Javadoc)
	 * @see org.opentdc.events.ServiceProvider#getMessage(java.lang.String)
	 */
	@Override
	public String getMessage(String id) throws NotFoundException,
			InternalServerErrorException {
		logger.info("getMessage(" + id + ")");
		EventModel _model = read(id);
		
		// create the FreeMarker data model
        Map<String, Object> _root = new HashMap<String, Object>();    
        _root.put("event", _model);
        
        // Merge data model with template   
        String _msg = FreeMarkerConfig.getProcessedTemplate(
        		_root, 
        		getTemplate(_model.getSalutation(), _model.getContact()));
		logger.info("getMessage(" + id + ") -> " + _msg);
		return _msg;
	}

	/* (non-Javadoc)
	 * @see org.opentdc.events.ServiceProvider#sendMessage(java.lang.String)
	 */
	@Override
	public void sendMessage(String id) throws NotFoundException,
			InternalServerErrorException {
		logger.info("sendMessage(" + id + ")");
		EventModel _model = read(id);

		emailSender.sendMessage(
			_model.getEmail(),
			getEmailAddress(_model.getContact()),
			SUBJECT,
			getMessage(id));
		logger.info("sent email message to " + _model.getEmail());
		_model.setId(null);
		_model.setInvitationState(InvitationState.SENT);
		update(id, _model);
	}

	/* (non-Javadoc)
	 * @see org.opentdc.events.ServiceProvider#sendAllMessages()
	 */
	@Override
	public void sendAllMessages() throws InternalServerErrorException {
		logger.info("sendAllMessages()");
		for (EventModel _model : index.values()) {
			emailSender.sendMessage(
				_model.getEmail(),
				getEmailAddress(_model.getContact()),
				SUBJECT,
				getMessage(_model.getId()));
			logger.info("sent email message to " + _model.getEmail());
			_model.setId(null);
			_model.setInvitationState(InvitationState.SENT);
			update(_model.getId(), _model);
			try {
				Thread.sleep(1000);
			} catch (InterruptedException _ex) {
				_ex.printStackTrace();
				throw new InternalServerErrorException(_ex.getMessage());
			}
		}
	}

	/* (non-Javadoc)
	 * @see org.opentdc.events.ServiceProvider#register(java.lang.String, java.lang.String)
	 */
	@Override
	public void register(
			String id,
			String comment) 
	throws NotFoundException, ValidationException {
		EventModel _event = read(id);
		if (_event.getInvitationState() == InvitationState.INITIAL) {
			throw new ValidationException("invitation <" + id + "> must be sent before being able to register");
		}
		if (_event.getInvitationState() == InvitationState.REGISTERED) {
			logger.warning("invitation <" + id + "> is already registered; ignoring re-registration");
		}
		_event.setInvitationState(InvitationState.REGISTERED);
		_event.setComment(comment);
		_event.setModifiedAt(new Date());
		_event.setModifiedBy(getPrincipal());
		index.put(id, _event);
		logger.info("register(" + id + ", " + comment + ") -> " + PrettyPrinter.prettyPrintAsJSON(_event));
		if (isPersistent) {
			exportJson(index.values());
		}
	}

	/* (non-Javadoc)
	 * @see org.opentdc.events.ServiceProvider#deregister(java.lang.String, java.lang.String)
	 */
	@Override
	public void deregister(
			String id,
			String comment) 
	throws NotFoundException, ValidationException {
		EventModel _event = read(id);
		if (_event.getInvitationState() == InvitationState.INITIAL) {
			throw new ValidationException("invitation <" + id + "> must be sent before being able to deregister");
		}
		if (_event.getInvitationState() == InvitationState.EXCUSED) {
			logger.warning("invitation <" + id + "> is already excused; ignoring deregistration");
		}
		_event.setInvitationState(InvitationState.EXCUSED);
		_event.setComment(comment);
		_event.setModifiedAt(new Date());
		_event.setModifiedBy(getPrincipal());
		index.put(id, _event);
		logger.info("deregister(" + id + ", " + comment + ") -> " + PrettyPrinter.prettyPrintAsJSON(_event));
		if (isPersistent) {
			exportJson(index.values());
		}
	}
}
