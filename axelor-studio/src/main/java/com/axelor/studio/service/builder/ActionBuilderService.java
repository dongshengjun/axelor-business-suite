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
package com.axelor.studio.service.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.meta.MetaStore;
import com.axelor.meta.db.MetaField;
import com.axelor.meta.db.MetaJsonField;
import com.axelor.meta.db.MetaJsonRecord;
import com.axelor.studio.db.ActionBuilder;
import com.axelor.studio.db.ActionBuilderLine;
import com.axelor.studio.db.repo.ActionBuilderLineRepository;
import com.axelor.studio.service.StudioMetaService;
import com.axelor.studio.service.wkf.WkfTrackingService;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

public class ActionBuilderService {

	private final Logger log = LoggerFactory.getLogger(getClass());
	
	private static final String INDENT = "\t";
	
	private List<StringBuilder> fbuilder = null;
	
	private int varCount = 0;

	@Inject
	private ActionBuilderLineRepository builderLineRepo;
	
	@Inject
	private StudioMetaService metaService;
	
	@Transactional
	public void build(ActionBuilder builder) {

		if (builder.getTypeSelect() == 0 || builder.getTypeSelect() == 1) {
			if(builder.getLines() == null || builder.getLines().isEmpty()) {
				return;
			}
		}

		log.debug("Processing action: {}, type: {}", builder.getName(), builder.getTypeSelect());
		
		
		
		String xml = getXml(builder);
		metaService.updateMetaAction(builder.getName(), "action-script", xml);
		MetaStore.clear();
	}
	
	private String getXml(ActionBuilder builder) {
		
		String name = builder.getName();
		String code = null;
		String lang = "js";
		String transactional = "true";
		
		if (builder.getTypeSelect() == 2) {
			code = "\n" + builder.getScriptText();
			if (builder.getScriptType() == 1) {
				lang = "groovy";
			}
			if (builder.getTransactional()) {
				transactional = "false";
			}
		}
		else {
			code = generateScriptCode(builder);
		}
		
		String xml = "<action-script name=\"" + name + "\" " 
				+ "id=\"" + name + "\" model=\"" 
				+ MetaJsonRecord.class.getName() + "\">\n\t" 
				+ "<script language=\"" + lang + "\" transactional=\"" + transactional + "\">\n\t<![CDATA[" 
				+ code + "\n\t]]>\n\t</script>\n</action-script>";
		return xml;
	}

	private String generateScriptCode(ActionBuilder builder) {
		
		StringBuilder stb = new StringBuilder();
		fbuilder = new ArrayList<StringBuilder>();
		varCount = 1;
		int level = 1;
		
		stb.append(format("var ctx = $request.context;", level));
		
		if (builder.getTypeSelect() == 0) {
			stb.append(format("var target = $json.create('" + builder.getTargetJsonModel().getName() + "');" , level));
			stb.append(format("target = setVar0(null, ctx, {});", level));
			stb.append(format("target = $json.save(target);", level));
			stb.append(format("Beans.get(" + WkfTrackingService.class.getName() + ".class).track(target);", level));
		}
		else {
			stb.append(format("var target = $json.create('" + builder.getMetaJsonModel().getName() + "');" , level));
			stb.append(format("target = setVar0(null, ctx, {});", level));
			stb.append(format("$response.setValues(target);", level));
		}
		
		stb.append(format("function setVar0($$, $, _$){", level));
		String bindings = addFieldsBinding("target", builder.getLines(), level+1);
		stb.append(bindings);
		stb.append(format("return target;", level + 1));
		stb.append(format("}", level));
		
		stb.append(Joiner.on("").join(fbuilder));
		
		return stb.toString();

	}

	private String format(String line, int level) {
		
		return "\n" + Strings.repeat(INDENT, level) + line;
	}

	private String addFieldsBinding(String target,  List<ActionBuilderLine> lines, int level) {
		
		StringBuilder stb = new StringBuilder();
		
		lines.sort(new Comparator<ActionBuilderLine>(){

			@Override
			public int compare(ActionBuilderLine l1, ActionBuilderLine l2) {
				if (l1.getDummy() && !l2.getDummy()){
					return -1;
				}
				if (!l1.getDummy() && l2.getDummy()) {
					return 1;
				}
				return 0;
			}
			
		});
		
		for (ActionBuilderLine line : lines) {
			
			String name = line.getName();
			String value = line.getValue();
			if (line.getDummy()) {
				stb.append(format("_$." +  name + " = " + value + ";", level)); 
				continue;
			}
			
			MetaJsonField jsonField = line.getMetaJsonField();
			MetaField metaField = line.getMetaField();
			
			if (jsonField != null 
					&& (jsonField.getTargetJsonModel() != null || jsonField.getTargetModel() != null)
					|| metaField != null && metaField.getRelationship() != null) {
				value = addRelationalBinding(line, true);
			}
			if (value.contains(".sum(")){
				value = getSumValue(value, line.getFilter());
			}
			
			
			
			String condition = line.getConditionText();
			if (condition != null) {
				stb.append(format("if("+ condition + "){" 
						+ target +"." + name + " = " 
						+ value + ";}", level));
			}
			else {
				stb.append(format(target +  "." + name + " = " + value + ";", level));
			}
		}
		
		return stb.toString();
				
	}
	

//	private String getValue(String typeName, String value, String filter, String context) {
//		
////		if (value.startsWith(":sum(")) {
////			return context + "." + getSumValue(value.substring(1), filter);
////		}
//		
//		if (value == null) {
//			return value;
//		}
//		
//		if (value.contains("$:")) {
//			return value.replaceAll("\\$:", "(" + context + " || {})" + ".");
//		}
//		if (value.contains("$ctx.")) {
//			return value.replaceAll("\\$ctx.", "ctx.");
//		}
//		
//		switch(typeName) {
//			case "date":
//				value =  "LocalDate.parse(\"" + StringEscapeUtils.escapeJava(value) + "\")";
//				break;
//			case "LocalDate":
//				value =  "LocalDate.parse(\"" + StringEscapeUtils.escapeJava(value) + "\")";
//				break;
//			case "ZonedDateTime":
//				value =  "ZonedDateTime.parse(\"" + StringEscapeUtils.escapeJava(value) + "\")";
//				break;
//			case "LocalTime":
//				value =  "LocalTime.parse(\"" + StringEscapeUtils.escapeJava(value) + "\")";
//				break;
//			case "datetime":
//				value =  "LocalDateTime.parse(\"" + StringEscapeUtils.escapeJava(value) + "\")";
//				break;
//			case "BigDecimal":
//				value =  "new BigDecimal(" + StringEscapeUtils.escapeJava(value) + ")";
//				break;
//			case "string":
//				value =  "\"" + StringEscapeUtils.escapeJava(value) + "\"";
//				break;
//			case "String":
//				value =  "\"" + StringEscapeUtils.escapeJava(value) + "\"";
//				break;
//		}
//		
//		return value;
//	}
	
	
	private String addRelationalBinding(ActionBuilderLine line, boolean json) {
		
		line = builderLineRepo.find(line.getId());
		String subCode = "null;";
		
		if (json) { 
			MetaJsonField jsonField = line.getMetaJsonField();
			if (jsonField == null) {
				return subCode;
			}
			switch(jsonField.getType()) {
				case "many-to-one":
					subCode = addM2OBinding(line, true, true);
					break;
				case "many-to-many":
					subCode = addM2MBinding(line);
					break;
				case "one-to-many":
					subCode = addO2MBinding(line);
					break;
				case "json-many-to-one":
					subCode = addJsonM2OBinding(line, true, true);
					break;
				case "json-many-to-many":
					subCode = addJsonM2MBinding(line);
					break;
				case "json-one-to-many":
					subCode = addJsonO2MBinding(line);
					break;
			}
		}
		else {
			MetaField metaField = line.getMetaField();
			if (metaField == null || metaField.getRelationship() == null) {
				return subCode;
			}
			switch(metaField.getRelationship()) {
				case "ManyToOne":
					subCode = addM2OBinding(line, true, true);
					break;
				case "OneToOne":
					subCode = addM2OBinding(line, true, true);
					break;
				case "ManyToMany":
					subCode = addM2MBinding(line);
					break;
				case "OneToMany":
					subCode = addO2MBinding(line);
					break;
			}
		}
		
		return subCode + "($," + line.getValue() + ", _$)";
	}
	
	private String getTargetModel(ActionBuilderLine line) {
		
		MetaJsonField jsonField = line.getMetaJsonField();
		
		String targetModel = null;
		if (jsonField != null && jsonField.getTargetModel() != null) {
			targetModel = jsonField.getTargetModel();
		}
		
		MetaField field = line.getMetaField();
		if (field != null && field.getTypeName() != null) {
			targetModel = field.getTypeName();
		}
		
		return targetModel;
	}
	
	private String getTargetJsonModel(ActionBuilderLine line) {
		
		MetaJsonField jsonField = line.getMetaJsonField();
		
		if (jsonField != null) {
			return jsonField.getTargetJsonModel().getName();
		}
		
		return null;
		
	}
	
	private String getSourceModel(ActionBuilderLine line) {
		
		MetaJsonField jsonField = line.getValueJson();
		
		String sourceModel = null;
		if (jsonField != null && jsonField.getTargetModel() != null) {
			sourceModel = jsonField.getTargetModel();
		}
		
		MetaField field = line.getValueField();
		if (field != null && field.getTypeName() != null) {
			sourceModel = field.getTypeName();
		}
		
		return sourceModel;
	}
	
	private String addM2OBinding(ActionBuilderLine line, boolean search, boolean filter) {
		
		String fname = "setVar" + varCount;
		varCount += 1;
		
		String tModel = getTargetModel(line);
		String srcModel = getSourceModel(line);
		
		StringBuilder stb = new StringBuilder();
		fbuilder.add(stb);
		if (tModel.contains(".")) {
			tModel = tModel.substring(tModel.lastIndexOf(".") + 1);
		}
		stb.append(format("",1));
		stb.append(format("function " + fname + "($$, $, _$){",1));
		stb.append(format("var val = null;", 2));
		if (srcModel != null) {
			stb.append(format("if ($ != null && $.id != null){", 2));
			srcModel = srcModel.substring(srcModel.lastIndexOf(".") + 1);
			stb.append(format("$ = $em.find(" + srcModel + ".class, $.id);", 3));
			log.debug("src model: {}, Target model: {}", srcModel, tModel);
			if (search && srcModel.equals(tModel)) {
				stb.append(format("val = $;", 3));
			}
			stb.append(format("}",2));
		}
		
		if (filter && line.getFilter() != null) {
			stb.append(format("var map = com.axelor.db.mapper.Mapper.toMap($);",2));
			stb.append(format("val = " + getQuery(tModel, line.getFilter(), false, false), 2));
		}
		
		List<ActionBuilderLine> lines = line.getSubLines();
		if (lines != null && !lines.isEmpty()) {
			stb.append(format("if (!val) {", 2));
			stb.append(format("val = new " + tModel + "();", 3));
			stb.append(format("}",2));
			stb.append(addFieldsBinding("val", lines, 2));
			stb.append(format("$em.persist(val);", 2));
		}
		stb.append(format("return val;", 2));
		stb.append(format("}", 1));
		
		return fname;
	}
	
	private String addM2MBinding(ActionBuilderLine line) {
		
		String fname = "setVar" + varCount;
		varCount += 1;
		StringBuilder stb = new StringBuilder();
		fbuilder.add(stb);
		stb.append(format("",1));
		stb.append(format("function " + fname + "($$, $, _$){",1));
		stb.append(format("var val  = new HashSet();", 2));
		if (line.getFilter() != null) {
			String model = getTargetModel(line);
			stb.append(format("var map = com.axelor.db.mapper.Mapper.toMap($$);",2));
			stb.append(format("val.addAll(" + getQuery(model, line.getFilter(), false, true) + ");", 2));
			stb.append(format("if(!val.empty){return val;}", 2));
		}
		
		stb.append(format("if(!$){return val;}", 2));
		stb.append(format("$.forEach(function(v){", 2));
		stb.append(format("v = " + addM2OBinding(line, true, false) + "($$, v, _$);", 3));
		stb.append(format("val.add(v);", 3));
		stb.append(format("})", 2));
		stb.append(format("return val;", 2));
		stb.append(format("}", 1));
		
		return fname;
	}
	
	private String addO2MBinding(ActionBuilderLine line) {
		
		String fname = "setVar" + varCount;
		varCount += 1;
		StringBuilder stb = new StringBuilder();
		fbuilder.add(stb);
		stb.append(format("",1));
		stb.append(format("function " + fname + "($$, $, _$){",1));
		stb.append(format("var val  = new ArrayList();", 2));
		stb.append(format("if(!$){return val;}", 2));
		stb.append(format("$.forEach(function(v){", 2));
		stb.append(format("v = " + addM2OBinding(line, false, false) + "($$, v, _$);", 3));
		stb.append(format("val.add(v);", 3));
		stb.append(format("})", 2));
		stb.append(format("return val;", 2));
		stb.append(format("}", 1));
		
		return fname;
	}
	
	private String addJsonM2OBinding(ActionBuilderLine line, boolean search, boolean filter) {
		
		String fname = "setVar" + varCount;
		varCount += 1;
		
		StringBuilder stb = new StringBuilder();
		fbuilder.add(stb);
		String model = getTargetJsonModel(line);
		stb.append(format("",1));
		stb.append(format("function " + fname + "($$, $, _$){",1));
		stb.append(format("var val = null;", 2));
		stb.append(format("if ($ != null && $.id != null){", 2));
		stb.append(format("$ = $json.find($.id);", 3));
		if (search) {
			stb.append(format("if ($.jsonModel.name == '" + model + "' ||  $.jsonModel == '" + model + "') {",3));
			stb.append(format("val = $;", 4));
			stb.append(format("}", 3));
		}
		stb.append(format("}",2));
		if (filter && line.getFilter() != null) {
			stb.append(format("val = " + getQuery(model, line.getFilter(),  true, false), 2));
		}
		List<ActionBuilderLine> lines = line.getSubLines();
		if (lines != null && !lines.isEmpty()) {
			stb.append(format("if (!val) {", 2));
			stb.append(format("val = $json.create('" + model + "');", 3));
			stb.append(format("}",2));
			stb.append(format("if($){$ = new com.axelor.rpc.JsonContext($)};", 2));
			stb.append(addFieldsBinding("val", lines, 2));
			stb.append(format("$json.save(val);", 2));
		}
		stb.append(format("return val;", 2));
		stb.append(format("}", 1));
		
		return fname;
	}
	
	private String addJsonM2MBinding(ActionBuilderLine line) {
		
		String fname = "setVar" + varCount;
		varCount += 1;
		StringBuilder stb = new StringBuilder();
		fbuilder.add(stb);
		stb.append(format("",1));
		stb.append(format("function " + fname + "($$, $, _$){",1));
		stb.append(format("var val  = new HashSet();", 2));
		if (line.getFilter() != null) {
			String model = getTargetJsonModel(line);
			stb.append(format("val.addAll(" + getQuery(model, line.getFilter(), true, true) + ");", 2));
			stb.append(format("if(!val.empty){return val;}", 2));
		}
		stb.append(format("if(!$){return val;}", 2));
		stb.append(format("$.forEach(function(v){", 2));
		stb.append(format("v = " + addJsonM2OBinding(line, true, false) + "($$, v, _$);", 3));
		stb.append(format("val.add(v);", 3));
		stb.append(format("})", 2));
		stb.append(format("return val;", 2));
		stb.append(format("}", 1));
		
		return fname;
	}
	
	private String addJsonO2MBinding(ActionBuilderLine line) {
		
		String fname = "setVar" + varCount;
		varCount += 1;
		StringBuilder stb = new StringBuilder();
		fbuilder.add(stb);
		stb.append(format("",1));
		stb.append(format("function " + fname + "($$, $, _$){",1));
		stb.append(format("var val  = new ArrayList();", 2));
		stb.append(format("if(!$){return val;}", 2));
		stb.append(format("$.forEach(function(v){", 2));
		stb.append(format("v = " + addJsonM2OBinding(line, false, false) + "($$, v, _$);", 3));
		stb.append(format("val.add(v);", 3));
		stb.append(format("})", 2));
		stb.append(format("return val;", 2));
		stb.append(format("}", 1));
		
		return fname;
	}
	
	private String getQuery(String model, String filter,  boolean json, boolean all) {
		
		
		if (model.contains(".")) {
			model = model.substring(model.lastIndexOf(".") + 1);
		}
		
		String nRecords = "fetchOne()";
		if (all) {
			nRecords = "fetch()";
		}
		
		String query = null;
		
		if (json){
			query = "$json.all('" + model + "').by(" + filter + ")." + nRecords;
		}
		else {
			query = "__repo__(" + model + ".class).all().filter(\"" + filter + "\").bind(map).bind(_$)." + nRecords;
		}
		
		return query;
	}
	
	private String getSumValue(String value, String filter) {
		
		value = value.substring(0,value.length() - 1);
		String[] expr = value.split("\\.sum\\(");
		
		String fname = "setVar" + varCount;
		varCount += 1;
		
		StringBuilder stb = new StringBuilder();
		stb.append(format("",1));
		stb.append(format("function " + fname + "($$, filter){",1));
		stb.append(format("var val  = 0", 2));
		stb.append(format("$$.forEach(function($){", 2));
		stb.append(format("if ($ instanceof MetaJsonRecord){ $ = new com.axelor.rpc.JsonContext($); }", 3));
		String val = "val += " + expr[1] + ";" ;
		if (filter != null) {
			val = "if(filter){" + val + "}";
		}
		stb.append(format(val, 3));
		stb.append(format("})", 2));
		stb.append(format("return val;", 2));
		stb.append(format("}", 1));
		
		fbuilder.add(stb);
		return fname + "(" + expr[0] + "," + filter + ")";

	}
	
}