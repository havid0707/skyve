package org.skyve.impl.web.service.smartclient;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.security.Principal;
import java.util.Enumeration;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.skyve.CORE;
import org.skyve.content.MimeType;
import org.skyve.domain.Bean;
import org.skyve.domain.ChildBean;
import org.skyve.domain.PersistentBean;
import org.skyve.domain.messages.Message;
import org.skyve.domain.messages.MessageException;
import org.skyve.domain.messages.NoResultsException;
import org.skyve.domain.messages.OptimisticLockException;
import org.skyve.domain.messages.OptimisticLockException.OperationType;
import org.skyve.domain.messages.SessionEndedException;
import org.skyve.domain.messages.ValidationException;
import org.skyve.impl.bind.BindUtil;
import org.skyve.impl.domain.messages.SecurityException;
import org.skyve.impl.generate.SmartClientGenerateUtils;
import org.skyve.impl.metadata.customer.CustomerImpl;
import org.skyve.impl.metadata.model.document.DocumentImpl;
import org.skyve.impl.metadata.repository.AbstractRepository;
import org.skyve.impl.metadata.repository.router.Router;
import org.skyve.impl.persistence.AbstractPersistence;
import org.skyve.impl.util.UtilImpl;
import org.skyve.impl.util.ValidationUtil;
import org.skyve.impl.web.AbstractWebContext;
import org.skyve.impl.web.ConversationUtil;
import org.skyve.impl.web.ServletConstants;
import org.skyve.impl.web.UserAgent;
import org.skyve.impl.web.UserAgentType;
import org.skyve.impl.web.WebUtil;
import org.skyve.metadata.MetaDataException;
import org.skyve.metadata.controller.DownloadAction;
import org.skyve.metadata.controller.ImplicitActionName;
import org.skyve.metadata.controller.ServerSideAction;
import org.skyve.metadata.controller.ServerSideActionResult;
import org.skyve.metadata.customer.Customer;
import org.skyve.metadata.model.Attribute;
import org.skyve.metadata.model.Persistent;
import org.skyve.metadata.model.document.Association;
import org.skyve.metadata.model.document.Bizlet;
import org.skyve.metadata.model.document.Document;
import org.skyve.metadata.module.Module;
import org.skyve.metadata.router.UxUi;
import org.skyve.metadata.router.UxUiSelector;
import org.skyve.metadata.user.User;
import org.skyve.metadata.view.View;
import org.skyve.metadata.view.View.ViewType;
import org.skyve.metadata.view.widget.bound.Parameter;
import org.skyve.util.Binder.TargetMetaData;
import org.skyve.util.Util;

public class SmartClientEditServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) 
	throws ServletException, IOException {
		UtilImpl.LOGGER.info("SmartClientEdit - get....");
		processRequest(request, response);
	}
	
	@Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
	throws ServletException, IOException  {
		UtilImpl.LOGGER.info("SmartClientEdit - post....");
		processRequest(request, response);
	}
	
	// NB - Never throw ServletException as this will halt the SmartClient Relogin flow.
	private static void processRequest(HttpServletRequest request, HttpServletResponse response) 
	throws IOException {
        response.setContentType(MimeType.json.toString());
        response.setCharacterEncoding(Util.UTF8);
        response.addHeader("Cache-control", "private,no-cache,no-store"); // never
		response.addDateHeader("Expires", 0); // never
		try (PrintWriter pw = response.getWriter()) {
	    	String operationType = request.getParameter("_operationType");
	        if (operationType == null) {
	        	pw.append("{}");
	        	return;
	        }
	        Operation operation = Operation.valueOf(operationType);

	        AbstractPersistence persistence = null;
	        try {
				try {
					// Start or continue a smartclient conversation
					AbstractWebContext webContext = null;
			        String key = request.getParameter(AbstractWebContext.CONTEXT_NAME);
			        if (key != null) {
			        	webContext = ConversationUtil.getCachedConversation(key, request, response);
			        	UtilImpl.LOGGER.info("USE OLD CONVERSATION!!!!");
			            persistence = webContext.getConversation();
			            persistence.setForThread();
			        }
			    	else {
			            // Create and inject any dependencies
			            webContext = new SmartClientWebContext(UUID.randomUUID().toString(), request, response);

			    		UtilImpl.LOGGER.info("START NEW CONVERSATION!!!!");
			            persistence = AbstractPersistence.get();
			            persistence.evictAllCached();
			            webContext.setConversation(persistence);
			    	}
			        webContext.setAction(request.getParameter(AbstractWebContext.ACTION_NAME));
			
					UserAgentType userAgentType = UserAgent.getType(request);
			        Router router = CORE.getRepository().getRouter();
					UxUi uxui = ((UxUiSelector) router.getUxuiSelector()).select(userAgentType, request);
					UtilImpl.LOGGER.info("UX/UI = " + uxui.getName());
					
			    	persistence.begin();
			    	Principal userPrincipal = request.getUserPrincipal();
			    	User user = WebUtil.processUserPrincipalForRequest(request, (userPrincipal == null) ? null : userPrincipal.getName(), true);
					if (user == null) {
						throw new SessionEndedException();
					}
			    	persistence.setUser(user);
	
			    	Customer customer = user.getCustomer();
			    	
			    	String formBinding = request.getParameter(AbstractWebContext.BINDING_NAME);
			    	if (formBinding != null) {
			    		formBinding = BindUtil.unsanitiseBinding(formBinding);
			    	}
			    	String gridBinding = request.getParameter(AbstractWebContext.GRID_BINDING_NAME);
			    	if (gridBinding != null) {
			    		gridBinding = BindUtil.unsanitiseBinding(gridBinding);
			    	}
			    	String source = request.getParameter(AbstractWebContext.SOURCE_NAME);
			    	String editIdCounter = request.getParameter(SmartClientWebContext.EDIT_ID_COUNTER);
			    	String createIdCounter = request.getParameter(SmartClientWebContext.CREATE_ID_COUNTER);
	
			    	SortedMap<String, Object> parameters = collectRequestParameters(request);
					
					String bizId = (String) parameters.get(Bean.DOCUMENT_ID);
					
					// the bean in the web context - the conversation context
			    	Bean contextBean = webContext.getCurrentBean();
	
			    	// the bean pointed to by the form binding
			    	Bean formBean = null;
			    	if (formBinding == null) {
			    		formBean = contextBean;
			    	}
			    	else {
				    	// NB if a new bean is being added to a datagrid, the binding points to the array,
				    	// not the element, as the element isn't even created yet
			    		// find the process bean
			    		Object formValue = BindUtil.get(contextBean, formBinding);
			    		if (formValue instanceof List<?>) {
			    			if (bizId != null) { // bizId is null if a new bean is being added to a datagrid
			    				formBean = BindUtil.getElementInCollection(contextBean, formBinding, bizId);
			    				if (formBean == null) {
			    					// Somehow the app coder has removed the form bean from its collection
			    					// when zoomed in on the collection
			    					throw new IllegalStateException("The bean with bizId " + bizId + 
			    														" has been removed from collection with binding " + formBinding);
			    				}
			    			}
			    		}
			    		else {
			    			formBean = (Bean) formValue;
			    		}
			    	}
	
			    	Module processModule = customer.getModule(request.getParameter(AbstractWebContext.MODULE_NAME));
			    	Document processDocument = processModule.getDocument(customer, request.getParameter(AbstractWebContext.DOCUMENT_NAME));
	
			    	Module formModule = processModule;
			    	Document formDocument = processDocument;
			    	if (formBean != null) {
				    	formModule = customer.getModule(formBean.getBizModule());
				    	formDocument = formModule.getDocument(customer, formBean.getBizDocument());
			    	}
	
			    	// the bean pointed to by the form (and grid binding {if present})
			    	Bean processBean = null;
			    	
		    		if ((formBean != null) && (gridBinding != null)) {
		    			processBean = BindUtil.getElementInCollection(formBean, gridBinding, bizId);
			    	}
		    		else {
		    			processBean = formBean;
		    		}
			    	
					if (! user.canAccessDocument(processDocument)) {
						throw new SecurityException(processDocument.getName() + " in module " + processModule.getName(),
														user.getName());
					}
	
					// we have a user defined action to run
					String actionName = webContext.getAction();
					if ((actionName != null) && 
							(! ImplicitActionName.OK.toString().equals(actionName)) &&
							(! ImplicitActionName.Save.toString().equals(actionName)) &&
							(! ImplicitActionName.ZoomOut.toString().equals(actionName)) &&
							(! ImplicitActionName.Print.toString().equals(actionName))) {
						UtilImpl.LOGGER.info("ACTION " + formBinding + " : " + gridBinding);
						if ((editIdCounter == null) || (createIdCounter == null)) {
							throw new ServletException("Request is malformed");
						}
						apply(webContext,
			                    user,
								customer, 
								formModule,
								formDocument,
								formBean,
								processDocument,
								processBean,
								formBinding,
								gridBinding,
								source,
								null,
								actionName, 
								Integer.parseInt(editIdCounter),
								Integer.parseInt(createIdCounter),
								parameters,
								persistence,
								uxui.getName(),
								pw);
					}
					// we have an implicit action to run
					else {
						ImplicitActionName action = (actionName == null) ? null : ImplicitActionName.valueOf(actionName);
						switch (operation) {
						case fetch:
							UtilImpl.LOGGER.info("FETCH with binding " + formBinding);
							if ((editIdCounter == null) || (createIdCounter == null)) {
								throw new ServletException("Request is malformed");
							}
							fetch(webContext,
					                user,
									customer,
									contextBean,
									processModule,
									processDocument,
									formBinding,
									source,
									(String) parameters.get(Bean.DOCUMENT_ID),
									Integer.parseInt(editIdCounter),
									Integer.parseInt(createIdCounter),
									action,
									parameters,
									persistence,
									uxui.getName(),
									pw);
							break;
						case add:
						case update:
							UtilImpl.LOGGER.info("ADD/UPDATE with binding " + formBinding + " : " + gridBinding);
							if ((editIdCounter == null) || (createIdCounter == null)) {
								throw new ServletException("Request is malformed");
							}
							apply(webContext,
				                    user, 
									customer, 
									formModule,
									formDocument,
									formBean,
									processDocument, 
									processBean,
									formBinding,
									gridBinding,
									source,
									action,
									null,
									Integer.parseInt(editIdCounter),
									Integer.parseInt(createIdCounter),
									parameters, 
									persistence,
									uxui.getName(),
									pw);
							break;
						case remove:
							UtilImpl.LOGGER.info("REMOVE with binding " + formBinding);
							PersistentBean beanToDelete = (PersistentBean) processBean;
							Bizlet<PersistentBean> bizletToDelete = ((DocumentImpl) processDocument).getBizlet(customer);
							remove(webContext,
									user,
					                customer, 
									processDocument, 
									beanToDelete, 
									bizletToDelete, 
									persistence, 
									pw);
							break;
						default:
						}
					}
				}
				catch (InvocationTargetException e) {
					throw e.getTargetException();
				}
			}
			catch (Throwable t) {
			    t.printStackTrace();
		    	if (persistence != null) {
		    		persistence.rollback();
		    	}
	
		    	produceErrorResponse(t, operation, true, pw);
			}
		    finally {
	    	    // commit and close (its already been serialized to the conversations cache if needed)
	    		if (persistence != null) {
	    			persistence.commit(true);
	    		}
		    }
		}
	}
    
	/**
	 * Pump out error text for smart client pages.
	 * @param t	The exception.
	 * @param operation	fetch, add, remove or update.
	 * @param includeBindings	Only edit views can use the bindings available in the error messages.
	 * 							If bindings are included for errors generated from listgrids operations, no errors are shown
	 * @param pw	To append to.
	 */
	static void produceErrorResponse(Throwable t, Operation operation, boolean includeBindings, PrintWriter pw) {
		if (t instanceof MessageException) {
			List<Message> ms = ((MessageException) t).getMessages();
			
    		// We need keys to send a -4 message back
	    	StringBuilder sb = new StringBuilder(128);
	    	sb.append("{\"response\":{\"status\":-4,\"errors\":{");
	    	if (includeBindings && pumpOutValidationErrors(ms, sb)) { // are there any keys in the message?
		    	sb.setLength(sb.length() - 1); // remove the comma
		    	pw.append(sb);
                pw.append('}');
	    	}
	    	else {
                pw.append("{\"response\":{\"status\":-1,");
                if (Operation.fetch.equals(operation)) {
                    pw.append("\"startRow\":0,\"endRow\":0,\"totalRows\":0,");
                }
                pw.append("\"data\":\"");
                appendErrorText("The action you requested cannot be performed because: ", ms, pw);
                pw.append('"');
	    	}
    	}
    	else {
            pw.append("{\"response\":{\"status\":-1,");
            if (Operation.fetch.equals(operation)) {
                pw.append("\"startRow\":0,\"endRow\":0,\"totalRows\":0,");
            }
            pw.append("data:\"An error occured while processing your request.<br/>");
    	
            String message = t.getMessage();
            if (message != null) {
                pw.append(SmartClientGenerateUtils.processString(message)).append('"');
            }
            else {
                pw.append("no error message...\"");
            }
    	}

    	pw.append("}}");
	}
	
	static void appendErrorText(String synopsis, List<Message> ms, PrintWriter pw) {
    	pw.append(synopsis).append("<br/><ul>");
    	for (Message m : ms) {
	    	pw.append("<li>");
	        pw.append(SmartClientGenerateUtils.processString(m.getText()));
	        pw.append("</li>");
    	}
    	pw.append("</ul>");
	}

    /**
     * 
     * @param e
     * @param sb
     * @return if there are any keys in the message
     */
    private static boolean pumpOutValidationErrors(List<Message> ms, StringBuilder sb) {
    	boolean result = false;

    	for (Message m : ms) {
	    	for (String binding : m.getBindings()) {
	    		result = true;
	    		// no '.' or '[' or ']' allowed in JSON identifiers
	    		sb.append('"').append(BindUtil.sanitiseBinding(binding)).append("\":\"");
	    		String message = m.getText();
	    		if (message == null) {
	    			sb.append("An error has occurred");
	    		}
	    		else {
		    		sb.append(SmartClientGenerateUtils.processString(m.getText()));
	    		}
	    		sb.append("\",");
	    	}
    	}
    	
    	return result;
    }
    

    @SuppressWarnings("unchecked")
	private static void fetch(AbstractWebContext webContext,
			                    User user,
		    					Customer customer,
								Bean contextBean,
								Module processModule,
								Document processDocument,
								String formBinding,
								String source, // the source of a rerender event
								String bizId,
								int editIdCounter, // the base number which is incremented to view component IDs for uniqueness
								int createIdCounter, // the base number which is incremented to view component IDs for uniqueness
								ImplicitActionName action,
								SortedMap<String, Object> parameters, 
								AbstractPersistence persistence,
								String uxui,
								PrintWriter pw)
	throws Exception {
    	Bizlet<Bean> processBizlet = ((DocumentImpl) processDocument).getBizlet(customer);
    	Bean processBean = null;
    	
    	if (formBinding == null) { // top level
	    	if (bizId == null) { // new instance
				// No security check is required as we are at the top of the conversation
				// If the user doesn't have create privilege, it will be stopped in SaveAction.
	    		processBean = processDocument.newInstance(user);

	    		applyNewParameters(customer,
	    							user,
	    							persistence,
	    							processModule,
	    							processDocument,
	    							processBean,
	    							parameters,
	    							uxui);
	    		
	    		if (action == null) { // callbacks not fired after a zoom out on the parent view post
					CustomerImpl internalCustomer = (CustomerImpl) customer;
					boolean vetoed = internalCustomer.interceptBeforePreExecute(ImplicitActionName.New, processBean, null, webContext);
					if (! vetoed) {
						if (processBizlet != null) {
							if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preExecute", "Entering " + processBizlet.getClass().getName() + ".preExecute: " + ImplicitActionName.New + ", " + processBean + ", null, " + webContext);
			    			processBean = processBizlet.preExecute(ImplicitActionName.New, processBean, null, webContext);
			    			if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preExecute", "Exiting " + processBizlet.getClass().getName() + ".preExecute: " + processBean);
						}
						internalCustomer.interceptAfterPreExecute(ImplicitActionName.New, processBean, null, webContext);
					}
	    		}
	    		else if (source != null) { // rerender event
					CustomerImpl internalCustomer = (CustomerImpl) customer;
					boolean vetoed = internalCustomer.interceptBeforePreRerender(source, processBean, webContext);
					if (! vetoed) {
						if (processBizlet != null) {
							if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preRerender", "Entering " + processBizlet.getClass().getName() + ".preRerender: " + source + ", " + processBean + ", " + webContext);
			    			processBizlet.preRerender(source, processBean, webContext);
			    			if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preRerender", "Exiting " + processBizlet.getClass().getName() + ".preRerender: " + processBean);
						}
						internalCustomer.interceptAfterPreRerender(source, processBean, webContext);
					}
	    		}
	    	}
	    	else { // persisted instance (or a zoomout)
	    		Persistent persistent = processDocument.getPersistent();
	    		boolean persistentDocument = (persistent != null) && (persistent.getName() != null);
	    		// if document is persistent, try to retrieve the instance
	    		if (persistentDocument) {
	    			processBean = persistence.retrieve(processDocument, bizId, false);
	    		}
	    		// if we have no process bean, this mean it aint persisted yet, its a zoomOut
	    		// We're at the top level, so just let the process bean be the contextBean
	    		if (processBean == null) { // not persisted
	    			processBean = contextBean;
	    		}
	    		// We got nothing! Either the bean has been deleted, or the user doesn't have read access on this
	    		if (processBean == null) {
	    			throw new NoResultsException();
	    		}
	    		if (persistentDocument) {
		    		if (! user.canReadBean(processBean.getBizId(), 
	    									processBean.getBizModule(), 
	    									processBean.getBizDocument(), 
	    									processBean.getBizCustomer(), 
	    									processBean.getBizDataGroupId(), 
	    									processBean.getBizUserId())) {
		    			throw new SecurityException("this data", user.getName());
		    		}
	    		}
	    		if (action == null) { // callbacks not fired after a zoom out on the parent view post
					CustomerImpl internalCustomer = (CustomerImpl) customer;
					boolean vetoed = internalCustomer.interceptBeforePreExecute(ImplicitActionName.Edit, processBean, null, webContext);
					if (! vetoed) {
						if (processBizlet != null) {
							if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preExecute", "Entering " + processBizlet.getClass().getName() + ".preExecute: " + ImplicitActionName.Edit + ", " + processBean + ", null, " + webContext);
			    			processBean = processBizlet.preExecute(ImplicitActionName.Edit, processBean, null, webContext);
			    			if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preExecute", "Exiting " + processBizlet.getClass().getName() + ".preExecute: " + processBean);
						}
						internalCustomer.interceptAfterPreExecute(ImplicitActionName.Edit, processBean, null, webContext);
					}
	    		}
	    		else if (source != null) { // rerender event
					CustomerImpl internalCustomer = (CustomerImpl) customer;
					boolean vetoed = internalCustomer.interceptBeforePreRerender(source, processBean, webContext);
					if (! vetoed) {
						if (processBizlet != null) {
							if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preRerender", "Entering " + processBizlet.getClass().getName() + ".preRerender: " + source + ", " + processBean + ", " + webContext);
			    			processBizlet.preRerender(source, processBean, webContext);
			    			if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preRerender", "Exiting " + processBizlet.getClass().getName() + ".preRerender: " + processBean);
						}
						internalCustomer.interceptAfterPreRerender(source, processBean, webContext);
					}
	    		}
	    	}
    	}
    	else { // sub form
    		Object referenceValue = BindUtil.get(contextBean, formBinding);

			// Find the parent bean
			Bean parentBean = contextBean;
			String parentBinding = formBinding;

			// Remove the last component from the binding to get the parent binding
			int lastDotIndex = parentBinding.lastIndexOf('.');
			if (lastDotIndex >= 0) {
				parentBinding = parentBinding.substring(0, lastDotIndex);
				parentBean = (Bean) BindUtil.get(contextBean, parentBinding);
			}

    		if (bizId == null) { // new instance
    			Module contextModule = customer.getModule(contextBean.getBizModule());
    			Document contextDocument = contextModule.getDocument(customer, contextBean.getBizDocument());
    			TargetMetaData target = BindUtil.getMetaDataForBinding(customer, contextModule, contextDocument, formBinding);
        		Attribute targetRelation = (target == null) ? null : target.getAttribute();
            	Persistent persistent = processDocument.getPersistent();

            	// check for create privilege if the collection is persistent and the collection document is persistent
            	if ((targetRelation != null) && targetRelation.isPersistent() && // collection is persistent
    	    			(persistent != null) && (persistent.getName() != null) &&  // collection document is persistent
    	    			(! user.canCreateDocument(processDocument))) {
    				throw new SecurityException("create this data", user.getName());
    			}

    			processBean = processDocument.newInstance(user);
	    		
	    		applyNewParameters(customer,
	    							user,
	    							persistence,
	    							processModule,
	    							processDocument,
	    							processBean,
	    							parameters,
	    							uxui);

	    		// Set the parent of a child bean, if applicable
	    		if (processBean instanceof ChildBean<?>) {
    				Document parentDocument = processDocument.getParentDocument(customer);
    				String parentModuleName = parentDocument.getOwningModuleName();
    				String parentDocumentName = parentDocument.getName();

    				// Check if processBean.setParent() can be called or not.
    				// The processBean may be a child of some other bean and just being added to another collection here.
    				// Or it could be a derived document, so need to check inheritance as well.
    				CustomerImpl internalCustomer = (CustomerImpl) customer;
    				Document parentBeanDocument = customer.getModule(parentBean.getBizModule()).getDocument(customer, parentBean.getBizDocument());
    				while (parentBeanDocument != null) {
        				if (parentModuleName.equals(parentBeanDocument.getOwningModuleName()) &&
        						parentDocumentName.equals(parentBeanDocument.getName())) {
        					((ChildBean<Bean>) processBean).setParent(parentBean);
        					parentBeanDocument = null;
        				}
        				else {
        					String baseDocumentName = internalCustomer.getBaseDocument(parentBeanDocument);
	        				if (baseDocumentName == null) {
	        					parentBeanDocument = null;
	        				}
	        				else {
		        				int dotIndex = baseDocumentName.indexOf('.');
		        				Module baseModule = customer.getModule(baseDocumentName.substring(0, dotIndex));
		        				parentBeanDocument = baseModule.getDocument(customer, baseDocumentName.substring(dotIndex + 1));
	        				}
        				}
    				}
    			}

	    		// call preExecute()
	    		if (action == null) { // callbacks not fired after a zoom out on the parent view post
					CustomerImpl internalCustomer = (CustomerImpl) customer;
					boolean vetoed = internalCustomer.interceptBeforePreExecute(ImplicitActionName.Add, processBean, parentBean, webContext);
					if (! vetoed) {
						if (processBizlet != null) {
							if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preExecute", "Entering " + processBizlet.getClass().getName() + ".preExecute: " + ImplicitActionName.Add + ", " + processBean + ", " + parentBean + ", " + webContext);
			    			processBean = processBizlet.preExecute(ImplicitActionName.Add, processBean, parentBean, webContext);
			    			if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preExecute", "Exiting " + processBizlet.getClass().getName() + ".preExecute: " + processBean);
						}
						internalCustomer.interceptAfterPreExecute(ImplicitActionName.Add, processBean, parentBean, webContext);
					}
	    		}
	    		else if (source != null) { // rerender event
					CustomerImpl internalCustomer = (CustomerImpl) customer;
					boolean vetoed = internalCustomer.interceptBeforePreRerender(source, processBean, webContext);
					if (! vetoed) {
						if (processBizlet != null) {
							if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preRerender", "Entering " + processBizlet.getClass().getName() + ".preRerender: " + source + ", " + processBean + ", " + webContext);
			    			processBizlet.preRerender(source, processBean, webContext);
			    			if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preRerender", "Exiting " + processBizlet.getClass().getName() + ".preRerender: " + processBean);
						}
						internalCustomer.interceptAfterPreRerender(source, processBean, webContext);
					}
	    		}
	    		
	    		// add the newInstance to the context bean
	    		if (referenceValue instanceof List<?>) {
	    			((List<Bean>) referenceValue).add(processBean);
	    		}
	    		else {
	    			BindUtil.set(contextBean, formBinding, processBean);
	    		}
    		}
    		else {
    			// We can't check for update privilege here as we don't know if the zoom in is read-only or not.
    			// Its up to the app coder to disable the UI if appropriate.

    			if (referenceValue instanceof List<?>) {
    				processBean = BindUtil.getElementInCollection(contextBean, formBinding, bizId);
    			}
    			else {
    				processBean = (Bean) referenceValue;
    			}
	    		if (action == null) { // callbacks not fired after a zoom out on the parent view post
					CustomerImpl internalCustomer = (CustomerImpl) customer;
					boolean vetoed = internalCustomer.interceptBeforePreExecute(ImplicitActionName.Edit, processBean, parentBean, webContext);
					if (! vetoed) {
						if (processBizlet != null) {
							if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preExecute", "Entering " + processBizlet.getClass().getName() + ".preExecute: " + ImplicitActionName.Edit + ", " + processBean + ", " + parentBean + ", " + webContext);
			    			processBean = processBizlet.preExecute(ImplicitActionName.Edit, processBean, parentBean, webContext);
			    			if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preExecute", "Exiting " + processBizlet.getClass().getName() + ".preExecute: " + processBean);
						}
						internalCustomer.interceptAfterPreExecute(ImplicitActionName.Edit, processBean, parentBean, webContext);
					}
	    		}
	    		else if (source != null) { // rerender event
					CustomerImpl internalCustomer = (CustomerImpl) customer;
					boolean vetoed = internalCustomer.interceptBeforePreRerender(source, processBean, webContext);
					if (! vetoed) {
		    			if (processBizlet != null) {
							if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preRerender", "Entering " + processBizlet.getClass().getName() + ".preRerender: " + source + ", " + processBean + ", " + webContext);
			    			processBizlet.preRerender(source, processBean, webContext);
			    			if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preRerender", "Exiting " + processBizlet.getClass().getName() + ".preRerender: " + processBean);
		    			}
						internalCustomer.interceptAfterPreRerender(source, processBean, webContext);
					}
	    		}
    		}
    	}
		try {
			StringBuilder message = new StringBuilder(256);
	    	message.append("{\"response\":{\"status\":0,\"startRow\":0,\"endRow\":0,\"totalRows\":1,\"data\":[");
			ViewJSONManipulator manipulator = new ViewJSONManipulator(user, 
																		processModule, 
																		processDocument, 
																		processDocument.getView(uxui, 
																									customer, 
																									processBean.isCreated() ? 
																										ViewType.edit.toString() : 
																										ViewType.create.toString()),
																		processBean,
																		editIdCounter,
																		createIdCounter,
																		false);
			manipulator.visit();

			webContext.setCurrentBean((formBinding == null) ? processBean : ((contextBean == null) ? processBean : contextBean));
			message.append(manipulator.toJSON(webContext, null));
			message.append("]}}");
			// append in one atomic operation so that if an error is thrown, the response isn't half-sent
			pw.append(message);
		}
		finally {
			// lastly put the conversation in the cache, after the response is sent
			// and all lazy loading of domain objects has been realised
			ConversationUtil.cacheConversation(webContext);
		}
    }
    
    /**
     * Collect the request parameters filtering out system parameters unsanitising bindings and converting nulls etc
     * @param request
     * @return sorted map of parameters
     */
    public static SortedMap<String, Object> collectRequestParameters(HttpServletRequest request) {
    	SortedMap<String, Object> result = new TreeMap<>();
		Enumeration<String> names = request.getParameterNames();
		while (names.hasMoreElements()) {
			String name = names.nextElement();
			if (SmartClientListServlet.ISC_META_DATA_PREFIX.equals(name) || 
					SmartClientListServlet.ISC_DATA_FORMAT.equals(name)) {
				continue;
			}
			String value = request.getParameter(name);
			if (name.charAt(0) != '_') {
				// no '.' allowed in smart client field names
				name = BindUtil.unsanitiseBinding(name);
				
				// "null" can be sent by Smart Client
				if (value != null) {
					if ((value.length() == 0) || "null".equals(value)) {
						value = null;
					}
				}
				result.put(name, value);
			}
		}
		return result;
    }

    /**
     * Set the declared view parameters into its backing bean.
     * @param customer
     * @param user
     * @param persistence
     * @param processModule	The backing bean's module
     * @param processDocument	The backing bean's document
     * @param processBean	The backing bean
     * @param parameters	The map of parameters to potentially apply
     * @param uxui	Used to get the view
     * @throws Exception
     */
    public static void applyNewParameters(Customer customer, 
	    									User user, 
	    									AbstractPersistence persistence, 
	    									Module processModule,
	    									Document processDocument, 
	    									Bean processBean,
	    									SortedMap<String, Object> parameters,
	    									String uxui)
    throws Exception {
		View view = processDocument.getView(uxui, 
												customer, 
												processBean.isCreated() ? 
													ViewType.edit.toString() : 
													ViewType.create.toString());
		TreeMap<String, String> newParameterNamesToBindings = new TreeMap<>();
		for (Parameter parameter : view.getParameters()) {
			newParameterNamesToBindings.put(parameter.getName(), parameter.getBinding());
		}
		
		if (! newParameterNamesToBindings.isEmpty()) { // we have new parameters to apply
			// apply any supplied (and allowed) parameters to the new instance
			for (String parameterBinding : parameters.keySet()) {
				if (newParameterNamesToBindings.containsKey(parameterBinding)) {
	    			Object parameterValue = parameters.get(parameterBinding);
	    			if (parameterValue != null) {
		    			TargetMetaData target = BindUtil.getMetaDataForBinding(customer, 
																				processModule, 
																				processDocument, 
																				parameterBinding);
		    			if (target != null) {
		    				Attribute attribute = target.getAttribute();
		    				if ((attribute instanceof Association) && (parameterValue instanceof String)) {
		    					// find the existing bean with retrieve
		    					Document referenceDocument = target.getDocument().getRelatedDocument(customer, 
																										attribute.getName());
		    					Bean parameterBean = persistence.retrieve(referenceDocument, 
																			(String) parameterValue, 
																			false);
		    					// NB parameterBean can be null if it wasn't found in the retrieve above
		    					if (parameterBean != null) {
			    					if (! user.canReadBean(parameterBean.getBizId(), 
			    											parameterBean.getBizModule(), 
			    											parameterBean.getBizDocument(), 
			    											parameterBean.getBizCustomer(), 
			    											parameterBean.getBizDataGroupId(),
			    											parameterBean.getBizUserId())) {
			    						throw new SecurityException("this data", user.getName());
			    					}
		    					}
		    					parameterValue = parameterBean;
		    				}
		    			}
	    			}
	    			
	    			// For the new parameters on the target edit view, if it has a binding defined,
	    			// use it, otherwise rely on the name.
	    			// This allows us to bind on something other than the parameter name given.
	    			String targetBinding = newParameterNamesToBindings.get(parameterBinding);
	    			if (targetBinding == null) {
	    				targetBinding = parameterBinding;
	    			}
	    			BindUtil.set(processBean, targetBinding, parameterValue);
				}
			}
		}
    }
    
	private static void apply(AbstractWebContext webContext,
		                        User user,
		    					Customer customer,
								Module formModule,
								Document formDocument,
								Bean formBean,
								Document processDocument,
								Bean processBean,
								String formBinding, // the response is in terms of this binding
								String gridBinding, // the processing may be in terms of this binding
								String source, // the source of a rerender event (if applicable)
								ImplicitActionName implicitAction,
								String customActionName,
								int editIdCounter, // the base number which is incremented to view component IDs for uniqueness
								int createIdCounter, // the base number which is incremented to view component IDs for uniqueness
								SortedMap<String, Object> parameters, 
								AbstractPersistence persistence,
								String uxui,
								PrintWriter pw)
	throws Exception {
		String mutableCustomActionName = customActionName;
		if (! ServletConstants.PUSH_ACTION_NAME.equals(mutableCustomActionName)) {
			// Apply the JSON values
	    	ViewJSONManipulator manipulator = new ViewJSONManipulator(user, 
																		formModule, 
																		formDocument, 
																		formDocument.getView(uxui, 
																								customer, 
																								formBean.isCreated() ? 
																									ViewType.edit.toString() : 
																									ViewType.create.toString()),
																		formBean, 
																		editIdCounter,
																		createIdCounter,
																		true);
			manipulator.visit();
			manipulator.applyJSON((String) parameters.get("bean"), persistence, webContext);
		}
		else {
			mutableCustomActionName = null;
		}
		
		Bean processedBean = processBean;
		
		// if we need to redirect once the XHR response is received by the browser, this will be not null
		String redirectUrl = null;
		
		if (implicitAction == null) { // not an implicit action
			if (mutableCustomActionName != null) { // a custom action
				if (! user.canExecuteAction(processDocument, mutableCustomActionName)) {
					throw new SecurityException(mutableCustomActionName, user.getName());
				}
	
				// execute an action
				ServerSideAction<Bean> serverSideAction = null;
				DownloadAction<Bean> downloadAction = null;
				AbstractRepository repository = AbstractRepository.get();
				try {
					serverSideAction = repository.getServerSideAction(customer, processDocument, mutableCustomActionName, true);
				}
				catch (@SuppressWarnings("unused") MetaDataException | ClassCastException e) {
					try {
						downloadAction = repository.getDownloadAction(customer, processDocument, mutableCustomActionName, true);
					}
					catch (@SuppressWarnings("unused") MetaDataException | ClassCastException e1) {
						throw new MetaDataException("Could not find " + mutableCustomActionName + " in document " + 
														processDocument.getName() + " as a server-side action or a download action");
					}
				}
				if (downloadAction != null) { // download action
					downloadAction.prepare(processedBean, webContext);
					redirectUrl = WebUtil.getDownloadActionUrl(mutableCustomActionName,
																processDocument.getOwningModuleName(),
																processDocument.getName(),
																webContext.getWebId(),
																formBinding,
																gridBinding,
																processBean.getBizId());
				}
				else if (serverSideAction != null) { // server-side action
					CustomerImpl internalCustomer = (CustomerImpl) customer;
					boolean vetoed = internalCustomer.interceptBeforeServerSideAction(processDocument,
																						mutableCustomActionName,
																						processedBean, 
																						webContext);
					if (! vetoed) {
						ServerSideActionResult<Bean> result = serverSideAction.execute(processedBean, webContext);
						internalCustomer.interceptAfterServerSideAction(processDocument,
																			mutableCustomActionName,
																			result, 
																			webContext);
						processedBean = result.getBean();
					}
				}
			}
			else if (source != null) { // rerender event
				CustomerImpl internalCustomer = (CustomerImpl) customer;
				boolean vetoed = internalCustomer.interceptBeforePreRerender(source, processedBean, webContext);
				if (! vetoed) {
					Bizlet<Bean> processBizlet = ((DocumentImpl) processDocument).getBizlet(customer);
					if (processBizlet != null) {
						if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preRerender", "Entering " + processBizlet.getClass().getName() + ".preRerender: " + source + ", " + processedBean + ", " + webContext);
						processBizlet.preRerender(source, processedBean, webContext);
						if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preRerender", "Exiting " + processBizlet.getClass().getName() + ".preRerender: " + processedBean);
					}
					internalCustomer.interceptAfterPreRerender(source, processedBean, webContext);
				}
			}
		}
		else { // an implicit action
			Bizlet<Bean> processBizlet = ((DocumentImpl) processDocument).getBizlet(customer);
			UtilImpl.LOGGER.info("PRE-EXECUTE on " + implicitAction);

			CustomerImpl internalCustomer = (CustomerImpl) customer;
			boolean vetoed = internalCustomer.interceptBeforePreExecute(implicitAction, processedBean, null, webContext);
			if (! vetoed) {
				if (processBizlet != null) {
					if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preExecute", "Entering " + processBizlet.getClass().getName() + ".preExecute: " + implicitAction + ", " + processedBean + ", null, " + webContext);
					processedBean = processBizlet.preExecute(implicitAction, processedBean, null, webContext);
					if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, processBizlet.getClass().getName(), "preExecute", "Exiting " + processBizlet.getClass().getName() + ".preExecute: " + processedBean);
				}
				internalCustomer.interceptAfterPreExecute(implicitAction, processedBean, null, webContext);
			}

			// We are zooming out, so run the required validation and security checks
			if (ImplicitActionName.ZoomOut.equals(implicitAction)) {
				// We cannot do security tests at this point because
				// ZoomOut is the only way out of the UI.
				// Create privilege is checked at instantiation time - on the zoom in operation

				ValidationUtil.validateBeanAgainstDocument(processDocument, processedBean);
				if (processBizlet != null) {
					ValidationUtil.validateBeanAgainstBizlet(processBizlet, processedBean);
				}
			}
			
			// we are saving, so run security checks and save the bean
			if ((formBinding == null) && (gridBinding == null) && // top level
					(ImplicitActionName.OK.equals(implicitAction) || ImplicitActionName.Save.equals(implicitAction)) && // Save or OK pressed
					(processedBean instanceof PersistentBean)) { // we have a persistent bean to apply
				if (processedBean.isNotPersisted() && (! user.canCreateDocument(processDocument))) {
					throw new SecurityException("create this data", user.getName());
				}
				else if (processedBean.isPersisted() && (! user.canUpdateDocument(processDocument))) {
					throw new SecurityException("update this data", user.getName());
				}
				processedBean = persistence.save(processDocument, (PersistentBean) processedBean);
			}
		}
		
		Bean beanToRender = formBean;
		
		// Set the processed bean value back into the list, if we have a grid binding
		if (gridBinding != null) {
			@SuppressWarnings("unchecked")
			List<Bean> gridList = (List<Bean>) BindUtil.get(formBean, gridBinding);
			int processedIndex = gridList.indexOf(processedBean);
			if (processedIndex < 0) { // new not in the list
				// look for old bean and replace it with the new
				int processIndex = gridList.indexOf(processBean);
				if (processIndex >= 0) { // found old bean
					gridList.set(processIndex, processedBean);
				}
			}
		}
		else {
			// If we are at the top level, 
			// then processBean, formBean and contextBean are all the same
			// Ensure that we update the webContext bean as 
			// it may have changed reference in the processing above
			if (formBinding == null) { // top level
				webContext.setCurrentBean(processedBean);
			}
			
			// The processBean may have changed reference in the processing above, 
			// so use it to render out the response.
			beanToRender = processedBean; 
		}
		
		View renderView = formDocument.getView(uxui, 
												customer, 
												beanToRender.isCreated() ? 
													ViewType.edit.toString() : 
													ViewType.create.toString());
		pumpOutResponse(webContext, user, formModule, formDocument, renderView, beanToRender, editIdCounter, createIdCounter, redirectUrl, pw);
	}

	private static void pumpOutResponse(AbstractWebContext webContext,
		                                    User user,
											Module formModule,
											Document formDocument,
											View formView,
											Bean formBean,
											int editIdCounter, // the base number which is incremented to view component IDs for uniqueness
											int createIdCounter, // the base number which is incremented to view component IDs for uniqueness
											String redirectUrl,
											PrintWriter pw) 
	throws Exception  {
		StringBuilder result = new StringBuilder(256);
		// Need to make a new JSON manipulator here to visit the view for the response
		// as conditions may have changed since applying changes to the bean
		ViewJSONManipulator manipulator = new ViewJSONManipulator(user, 
																	formModule, 
																	formDocument, 
																	formView,
																	formBean,
																	editIdCounter,
																	createIdCounter,
																	false);
		manipulator.visit();
		try {
			result.append("{\"response\":{\"status\":0,\"data\":");
			result.append(manipulator.toJSON(webContext, redirectUrl));
			result.append("}}");

			// append in one atomic operation so that if an error is thrown, the response isn't half-sent
			pw.append(result);
		}
		finally {
			// lastly put the conversation in the cache, after the response is sent
			// and all lazy loading of domain objects has been realised
			ConversationUtil.cacheConversation(webContext);
		}
	}
	
	private static void remove(AbstractWebContext webContext,
								User user,
		                        Customer customer,
								Document processDocument,
								PersistentBean beanToDelete,
								Bizlet<PersistentBean> bizlet,
								AbstractPersistence persistence,
								PrintWriter pw)
	throws Exception {
		if (! user.canDeleteDocument(processDocument)) {
			throw new SecurityException("delete this data", user.getName());
		}
		
		// Ensure that we are working on the latest of everything and no related entities are pointing to old data
		persistence.evictAllCached();
		PersistentBean persistentBeanToDelete = persistence.retrieve(processDocument, 
																		beanToDelete.getBizId(), 
																		false);

		if (persistentBeanToDelete == null) { // deleted by another user
			throw new ValidationException(new Message("Failed to delete this information as it was already deleted by someone else after you looked at it."));
		}

		if (! user.canReadBean(persistentBeanToDelete.getBizId(), 
								persistentBeanToDelete.getBizModule(), 
								persistentBeanToDelete.getBizDocument(), 
								persistentBeanToDelete.getBizCustomer(), 
								persistentBeanToDelete.getBizDataGroupId(), 
								persistentBeanToDelete.getBizUserId())) {
			throw new SecurityException("read this data", user.getName());
		}
		
		if (! persistentBeanToDelete.getBizLock().equals(beanToDelete.getBizLock())) {
			throw new OptimisticLockException(customer, 
												OperationType.delete, 
												persistentBeanToDelete.getBizLock());
		}
		
		// Run preExecute after the copy is taken, in case we rollback
		UtilImpl.LOGGER.info("PRE-EXECUTE on " + ImplicitActionName.Delete);
		CustomerImpl internalCustomer = (CustomerImpl) customer;
		boolean vetoed = internalCustomer.interceptBeforePreExecute(ImplicitActionName.Delete, 
																		persistentBeanToDelete, 
																		null,
																		webContext);
		if (! vetoed) {
			if (bizlet != null) {
				if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, bizlet.getClass().getName(), "preExecute", "Entering " + bizlet.getClass().getName() + ".preExecute: " + ImplicitActionName.Delete + ", " + persistentBeanToDelete + ", null, " + webContext);
				persistentBeanToDelete = bizlet.preExecute(ImplicitActionName.Delete, 
															persistentBeanToDelete, 
															null,
															webContext);
				if (UtilImpl.BIZLET_TRACE) UtilImpl.LOGGER.logp(Level.INFO, bizlet.getClass().getName(), "preExecute", "Exiting " + bizlet.getClass().getName() + ".preExecute: " + persistentBeanToDelete);
			}
			internalCustomer.interceptAfterPreExecute(ImplicitActionName.Delete, 
														persistentBeanToDelete, 
														null,
														webContext);
		}
		
		persistence.delete(processDocument, persistentBeanToDelete);

		ConversationUtil.cacheConversation(webContext);

		pw.append("{\"response\":{\"status\":0}}");
	}
}
