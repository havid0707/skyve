package org.skyve.impl.metadata.view.event;

import java.util.List;

import org.skyve.impl.metadata.view.event.EventAction;
import org.skyve.impl.metadata.view.event.EventSource;

public interface Removable extends EventSource {
	public List<EventAction> getRemovedActions();
}
