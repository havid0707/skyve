package org.skyve.impl.generate.pwa.react;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

import org.skyve.CORE;
import org.skyve.metadata.customer.Customer;
import org.skyve.metadata.model.document.Document;
import org.skyve.metadata.module.Module;
import org.skyve.metadata.repository.Repository;
import org.skyve.metadata.user.User;
import org.skyve.metadata.view.View;
import org.skyve.metadata.view.View.ViewType;

public class ReactEditView extends ReactComponent {
	private Module module;
	private Document document;
	private View createView;
	private View editView;
	
	public ReactEditView(ReactGenerator generator, String moduleName, String componentName) {
		super(generator, moduleName, componentName);
	}

	public void setViews(Module module, Document document) {
		this.module = module;
		this.document = document;
		Repository r = CORE.getRepository();
		Customer c = CORE.getCustomer();
        editView = r.getView(generator.uxui, c, document, ViewType.edit.toString());
        createView = r.getView(generator.uxui, c, document, ViewType.create.toString());
 	}

	@Override
	protected void create(FileWriter fw) throws IOException {
		Map<String, String> imports = new TreeMap<>();
		String editJsx = null;
		String createJsx = null;

		// Wrap edit and create views in a Fragment if necessary
		boolean bothViews = (editView != null) && (createView != null);

		User u = CORE.getUser();
		if (editView != null) {
			ReactViewRenderer v = new PrimeReactViewRenderer(u, module, document, editView, imports, bothViews);
			v.visit();
			editJsx = v.getResult().toString();
		}
		if (createView != null) {
			ReactViewRenderer v = new PrimeReactViewRenderer(u, module, document, createView, imports, bothViews);
			v.visit();
			createJsx = v.getResult().toString();
		}

		fw.write("import React from 'react';\n");
		fw.write("import {View} from '../../View';\n");
		
		for (String key : imports.keySet()) {
			fw.append("import ").append(key).append(" from '").append(imports.get(key)).append("';\n");
		}
		fw.write("\n");
		
		fw.append("export class ").append(moduleName).append(componentName).append(" extends View {\n");
		fw.write("\tconstructor() {\n");
		fw.write("\t\tsuper();\n");
		fw.write("\t\tthis.state = {};\n");
		fw.write("\t}\n\n");
		
		fw.write("\tcomponentDidMount() {\n");
		fw.append("\t\tthis.edit('").append(moduleName).append("', '").append(document.getName()).append("', this.props.match.params.bizId)\n");
		fw.write("\t\t\t.then(data => this.setState(data[0]));\n");
	    fw.write("\t}\n\n");

		fw.write("\trender() {\n");

		if (bothViews) {
			fw.write("\t\tif (this.state.created) {\n");
			fw.write("\t\t\treturn (\n");
			fw.write((editJsx == null) ? "" : editJsx.toString());
			fw.write("\t\t\t);\n");
			fw.write("\t\t}\n");
			fw.write("\t\telse {\n");
			fw.write("\t\t\treturn (\n");
			fw.write((createJsx == null) ? "" : createJsx.toString());
			fw.write("\t\t\t);\n");
			fw.write("\t\t}\n");
		}
		else {
			fw.write("\t\treturn (\n");
			if (editView != null) {
				fw.write((editJsx == null) ? "" : editJsx.toString());
			}
			if (createView != null) {
				fw.write((createJsx == null) ? "" : createJsx.toString());
			}
			fw.write("\t\t);\n");
		}

		fw.write("\t}\n");
		fw.write("}\n");
	}
}
