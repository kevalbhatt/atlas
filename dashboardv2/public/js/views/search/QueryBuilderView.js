/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

define(['require',
    'backbone',
    'hbs!tmpl/search/QueryBuilder_tmpl',
    'utils/Utils',
    'utils/CommonViewFunction',
    'query-builder',
    'daterangepicker'
], function(require, Backbone, QueryBuilder_Tmpl, Utils, CommonViewFunction) {

    var QueryBuilderView = Backbone.Marionette.LayoutView.extend(
        /** @lends QueryBuilderView */
        {
            _viewName: 'QueryBuilderView',

            template: QueryBuilder_Tmpl,



            /** Layout sub regions */
            regions: {},


            /** ui selector cache */
            ui: {
                "builder": "#builder"
            },
            /** ui events hash */
            events: function() {
                var events = {};
                return events;
            },
            /**
             * intialize a new QueryBuilderView Layout
             * @constructs
             */
            initialize: function(options) {
                _.extend(this, _.pick(options, 'attrObj', 'value', 'typeHeaders', 'entityDefCollection', 'enumDefCollection', 'tag'));
                this.attrObj = _.sortBy(this.attrObj, 'name');
                this.filterType = this.tag ? 'tagFilters' : 'entityFilters';
            },
            bindEvents: function() {},
            getOperator: function(type) {
                var obj = {
                    operators: null
                }
                if (type === "string") {
                    obj.operators = ['=', '!=', 'contains', 'begins_with', 'ends_with'];
                }
                if (type === "date" || type === "int" || type === "byte" || type === "short" || type === "long" || type === "float" || type === "double") {
                    obj.operators = ['=', '!=', '>', '<', '>=', '<='];
                }
                if (type === "enum" || type === "boolean") {
                    obj.operators = ['=', '!='];
                }
                return obj;
            },
            isPrimitive: function(type) {
                if (type === "int" || type === "byte" || type === "short" || type === "long" || type === "float" || type === "double" || type === "string" || type === "boolean" || type === "date") {
                    return true;
                }
                return false;
            },
            getObjDef: function(attrObj, rules) {
                var obj = {
                    id: attrObj.name,
                    label: attrObj.name.capitalize(),
                    type: attrObj.typeName
                };
                if (obj.type === "date") {
                    obj['plugin'] = 'daterangepicker';
                    obj['plugin_config'] = {
                        "singleDatePicker": true,
                        "showDropdowns": true,
                        "timePicker": true,
                        locale: {
                            format: 'MM/DD/YYYY h:mm A'
                        }
                    };
                    if (rules) {
                        var valueObj = _.find(rules, { id: obj.id });
                        if (valueObj) {
                            obj.plugin_config["startDate"] = valueObj.value;
                        }
                    }
                    _.extend(obj, this.getOperator(obj.type));
                    return obj;
                }
                if (this.isPrimitive(obj.type)) {
                    if (obj.type === "long" || obj.type === "float") {
                        obj.type = "double";
                    }
                    if (obj.type === "int" || obj.type === "byte" || obj.type === "short") {
                        obj.type = "integer";
                    }
                    if (obj.type === "boolean") {
                        obj['input'] = 'select';
                        obj['values'] = ['true', 'false'];
                    }
                    _.extend(obj, this.getOperator(obj.type));
                    return obj;
                }
                var enumObj = this.enumDefCollection.fullCollection.find({ name: obj.type });
                if (enumObj) {
                    obj.type = "string";
                    obj['input'] = 'select';
                    var value = [];
                    _.each(enumObj.get('elementDefs'), function(o) {
                        value.push(o.value)
                    })
                    obj['values'] = value;
                    _.extend(obj, this.getOperator('enum'));
                    return obj;
                }
            },
            onRender: function() {
                var that = this,
                    filters = [];
                if (this.value) {
                    var rules_widgets = CommonViewFunction.attributeFilter.extractUrl(this.value[this.filterType]);
                }
                _.each(this.attrObj, function(obj) {
                    var returnObj = that.getObjDef(obj, rules_widgets);
                    if (returnObj) {
                        filters.push(returnObj);
                    }
                });
                filters = _.uniq(filters, 'id');
                if (filters && !_.isEmpty(filters)) {
                    this.ui.builder.queryBuilder({
                        plugins: ['bt-tooltip-errors'],
                        filters: filters,
                        select_placeholder: '--Select Attribute--',
                        allow_empty: true,
                        conditions: ['AND'],
                        allow_groups: false,
                        allow_empty: true,
                        operators: [
                            { type: '=', nb_inputs: 1, multiple: false, apply_to: ['number', 'string', 'boolean'] },
                            { type: '!=', nb_inputs: 1, multiple: false, apply_to: ['number', 'string', 'boolean'] },
                            { type: '>', nb_inputs: 1, multiple: false, apply_to: ['number', 'string', 'boolean'] },
                            { type: '<', nb_inputs: 1, multiple: false, apply_to: ['number', 'string', 'boolean'] },
                            { type: '>=', nb_inputs: 1, multiple: false, apply_to: ['number', 'string', 'boolean'] },
                            { type: '<=', nb_inputs: 1, multiple: false, apply_to: ['number', 'string', 'boolean'] },
                            { type: 'contains', nb_inputs: 1, multiple: false, apply_to: ['string'] },
                            { type: 'begins_with', nb_inputs: 1, multiple: false, apply_to: ['string'] },
                            { type: 'ends_with', nb_inputs: 1, multiple: false, apply_to: ['string'] }
                        ],
                        lang: {
                            add_rule: 'Add filter',
                            add_group: 'Add filter group'
                        },
                        rules: rules_widgets
                    });
                    this.$('.rules-group-header .btn-group.pull-right.group-actions').toggleClass('pull-right pull-left');
                } else {
                    this.ui.builder.html('<h4>No Attributes are available !</h4>')
                }
            }
        });
    return QueryBuilderView;
});