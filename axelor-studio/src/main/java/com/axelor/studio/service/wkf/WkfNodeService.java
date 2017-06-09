/**
 * Axelor Business Solutions
 *
 * Copyright (C) 2016 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.studio.service.wkf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.auth.db.Permission;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.repo.PermissionRepository;
import com.axelor.meta.db.MetaAction;
import com.axelor.meta.db.MetaJsonField;
import com.axelor.meta.db.MetaModel;
import com.axelor.meta.db.MetaSelect;
import com.axelor.meta.db.MetaSelectItem;
import com.axelor.meta.db.repo.MetaModelRepository;
import com.axelor.meta.db.repo.MetaSelectRepository;
import com.axelor.studio.db.WkfNode;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

/**
 * Service handle processing of WkfNode. From wkfNode it generates status field
 * for related model. It will generate field's selection according to nodes and
 * add field in related ViewBuilder. Creates permissions for status related with
 * node and assign to roles selected in node. Add status menus(MenuBuilder)
 * according to menu details in WkfNode.
 * 
 * @author axelor
 *
 */
class WkfNodeService {

	private WkfService wkfService;

	private final Logger log = LoggerFactory.getLogger(getClass());

	private List<String[]> nodeActions;

	@Inject
	private MetaModelRepository metaModelRepo;

	@Inject
	private PermissionRepository permissionRepo;

	@Inject
	private MetaSelectRepository metaSelectRepo;
	
	@Inject
	protected WkfNodeService(WkfService wkfService) {
		this.wkfService = wkfService;
	}

	/**
	 * Root method to access the service. It start processing of WkfNode and
	 * call different methods for that.
	 */
	protected List<String[]> process() {

		MetaJsonField statusField = wkfService.workflow.getStatusField();
		MetaSelect metaSelect = addMetaSelect(statusField);

		nodeActions = new ArrayList<String[]>();
		String defaultValue = processNodes(metaSelect, statusField);
		statusField.setDefaultValue(defaultValue);
		
		return nodeActions;
	}

	/**
	 * Add MetaSelect in statusField, if MetaSelect of field is null.
	 * 
	 * @param statusField
	 *            MetaField to update with MetaSelect.
	 * @return MetaSelect of statusField.
	 */
	@Transactional
	public MetaSelect addMetaSelect(MetaJsonField statusField) {

		String selectName = wkfService.getSelectName();
		
		MetaSelect metaSelect = metaSelectRepo.findByName(selectName);
		if (metaSelect == null) {
			metaSelect = new MetaSelect(selectName);
			metaSelect = metaSelectRepo.save(metaSelect);
		}
		
		if (metaSelect.getItems() == null) {
			metaSelect.setItems(new ArrayList<MetaSelectItem>());
		}
		
		metaSelect.clearItems();

		return metaSelect;
	}

	/**
	 * Method remove old options from metaSelect options if not found in
	 * nodeList.
	 * 
	 * @param metaSelect
	 *            MetaSelect to process.
	 * @param nodeList
	 *            WkfNode list to compare.
	 * @return Updated MetaSelect.
	 */
	private MetaSelect removeOldOptions(MetaSelect metaSelect,
			List<WkfNode> nodeList) {

		log.debug("Cleaning meta select: {}", metaSelect.getName());

		List<MetaSelectItem> itemsToRemove = new ArrayList<MetaSelectItem>();

		Iterator<MetaSelectItem> itemIter = metaSelect.getItems().iterator();

		while (itemIter.hasNext()) {
			MetaSelectItem item = itemIter.next();
			boolean found = false;
			for (WkfNode node : nodeList) {
				if (item.getValue().equals(node.getSequence().toString())) {
					found = true;
					break;
				}
			}

			if (!found) {
				itemsToRemove.add(item);
				itemIter.remove();
			}

		}

		return metaSelect;
	}


	/**
	 * Add or update items in MetaSelect according to WkfNodes.
	 * 
	 * @param metaSelect
	 *            MetaSelect to update.
	 * @return Return first item as default value for wkfStatus field.
	 */
	private String processNodes(MetaSelect metaSelect, MetaJsonField statusField) {

		List<WkfNode> nodeList = wkfService.workflow.getNodes();
		
		String defaultValue = null;
		removeOldOptions(metaSelect, nodeList);
		
		for (WkfNode node : nodeList) {
			
			String option = node.getSequence().toString();
			MetaSelectItem metaSelectItem = getMetaSelectItem(metaSelect,
					option);
			if (metaSelectItem == null) {
				metaSelectItem = new MetaSelectItem();
				metaSelectItem.setValue(option);
				metaSelect.addItem(metaSelectItem);
			}

			metaSelectItem.setTitle(node.getTitle());
			metaSelectItem.setOrder(node.getSequence());

			if (defaultValue == null) {
				defaultValue = metaSelectItem.getValue();
				log.debug("Default value set: {}", defaultValue);
			}

			List<String[]> actions = new ArrayList<String[]>();
			
			if (node.getMetaActionSet() != null) {
				Stream<MetaAction> actionStream = node.getMetaActionSet().stream().sorted(Comparator.comparing(MetaAction::getSequence));
				actionStream.forEach(metaAction -> actions.add(new String[]{metaAction.getName()}));
			}
			
			if (!actions.isEmpty()) {
				String name = getActionName(node.getName());
				String value = node.getSequence().toString();
				if (statusField.getType().equals("string")) {
					value = "'" + value + "'";
				}
				String condition = statusField.getName() + " == " + value;
				nodeActions.add(new String[]{name,condition});
				this.wkfService.updateActionGroup(name, actions);
			}

		}

		return defaultValue;

	}


	public String getActionName(String node) {

		String name = wkfService.inflector.simplify(node);
		name = name.toLowerCase().replace(" ", "-");
		name = "action-group-" + wkfService.wkfId + "-" + name;

		return name;
	}

	/**
	 * Method to save MetaModel
	 * 
	 * @param metaModel
	 *            Model to save.
	 * @return Saved MetaModel
	 */
	@Transactional
	public MetaModel saveModel(MetaModel metaModel) {
		return metaModelRepo.save(metaModel);
	}

	/**
	 * Fetch MetaSelectItem from MetaSelect according to option.
	 * 
	 * @param metaSelect
	 *            MetaSelect to search for item.
	 * @param option
	 *            Option to search.
	 * @return MetaSelctItem found or null.
	 */
	private MetaSelectItem getMetaSelectItem(MetaSelect metaSelect,
			String option) {

		for (MetaSelectItem selectItem : metaSelect.getItems()) {
			if (selectItem.getValue().equals(option)) {
				return selectItem;
			}
		}

		return null;

	}

	/**
	 * Method remove old permission according to name of permission.
	 * 
	 * @param name
	 *            Permission name.
	 */
	@Transactional
	public void clearOldPermissions(String name) {

		Permission permission = permissionRepo.findByName(name);

		if (permission != null) {
			List<Role> oldRoleList = wkfService.roleRepo.all()
					.filter("self.permissions.id = ?1", permission.getId())
					.fetch();
			for (Role role : oldRoleList) {
				role.removePermission(permission);
				wkfService.roleRepo.save(role);
			}
		}
	}
	

}