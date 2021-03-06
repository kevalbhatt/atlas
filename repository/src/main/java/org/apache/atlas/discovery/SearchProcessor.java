/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.discovery;

import org.apache.atlas.ApplicationProperties;
import org.apache.atlas.AtlasException;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.discovery.SearchParameters;
import org.apache.atlas.model.discovery.SearchParameters.FilterCriteria;
import org.apache.atlas.model.discovery.SearchParameters.FilterCriteria.Condition;
import org.apache.atlas.model.typedef.AtlasBaseTypeDef;
import org.apache.atlas.repository.Constants;
import org.apache.atlas.repository.graphdb.*;
import org.apache.atlas.repository.store.graph.v1.AtlasGraphUtilsV1;
import org.apache.atlas.type.AtlasEntityType;
import org.apache.atlas.type.AtlasStructType;
import org.apache.atlas.type.AtlasStructType.AtlasAttribute;
import org.apache.atlas.util.AtlasGremlinQueryProvider;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

public abstract class SearchProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(SearchProcessor.class);

    public static final Pattern STRAY_AND_PATTERN          = Pattern.compile("(AND\\s+)+\\)");
    public static final Pattern STRAY_OR_PATTERN           = Pattern.compile("(OR\\s+)+\\)");
    public static final Pattern STRAY_ELIPSIS_PATTERN      = Pattern.compile("(\\(\\s*)\\)");
    public static final int     MAX_RESULT_SIZE            = getApplicationProperty(Constants.INDEX_SEARCH_MAX_RESULT_SET_SIZE, 150);
    public static final int     MAX_QUERY_STR_LENGTH_TYPES = getApplicationProperty(Constants.INDEX_SEARCH_TYPES_MAX_QUERY_STR_LENGTH, 512);
    public static final int     MAX_QUERY_STR_LENGTH_TAGS  = getApplicationProperty(Constants.INDEX_SEARCH_TAGS_MAX_QUERY_STR_LENGTH, 512);
    public static final String  AND_STR         = " AND ";
    public static final String  EMPTY_STRING    = "";
    public static final String  SPACE_STRING    = " ";
    public static final String  BRACE_OPEN_STR  = "(";
    public static final String  BRACE_CLOSE_STR = ")";

    private static final Map<SearchParameters.Operator, String> OPERATOR_MAP = new HashMap<>();

    static
    {
        OPERATOR_MAP.put(SearchParameters.Operator.LT,"v.\"%s\": [* TO %s}");
        OPERATOR_MAP.put(SearchParameters.Operator.GT,"v.\"%s\": {%s TO *]");
        OPERATOR_MAP.put(SearchParameters.Operator.LTE,"v.\"%s\": [* TO %s]");
        OPERATOR_MAP.put(SearchParameters.Operator.GTE,"v.\"%s\": [%s TO *]");
        OPERATOR_MAP.put(SearchParameters.Operator.EQ,"v.\"%s\": %s");
        OPERATOR_MAP.put(SearchParameters.Operator.NEQ,"-" + "v.\"%s\": %s");
        OPERATOR_MAP.put(SearchParameters.Operator.IN, "v.\"%s\": (%s)"); // this should be a list of quoted strings
        OPERATOR_MAP.put(SearchParameters.Operator.LIKE, "v.\"%s\": (%s)"); // this should be regex pattern
        OPERATOR_MAP.put(SearchParameters.Operator.STARTS_WITH, "v.\"%s\": (%s*)");
        OPERATOR_MAP.put(SearchParameters.Operator.ENDS_WITH, "v.\"%s\": (*%s)");
        OPERATOR_MAP.put(SearchParameters.Operator.CONTAINS, "v.\"%s\": (*%s*)");
    }

    protected final SearchContext   context;
    protected       SearchProcessor nextProcessor;


    protected SearchProcessor(SearchContext context) {
        this.context = context;
    }

    public void addProcessor(SearchProcessor processor) {
        if (nextProcessor == null) {
            nextProcessor = processor;
        } else {
            nextProcessor.addProcessor(processor);
        }
    }

    public abstract List<AtlasVertex> execute();

    public void filter(List<AtlasVertex> entityVertices) {
        if (nextProcessor != null && CollectionUtils.isNotEmpty(entityVertices)) {
            nextProcessor.filter(entityVertices);
        }
    }


    protected void processSearchAttributes(AtlasStructType structType, FilterCriteria filterCriteria, Set<String> indexFiltered, Set<String> graphFiltered, Set<String> allAttributes) {
        if (structType == null || filterCriteria == null) {
            return;
        }

        Condition            filterCondition = filterCriteria.getCondition();
        List<FilterCriteria> criterion       = filterCriteria.getCriterion();

        if (filterCondition != null && CollectionUtils.isNotEmpty(criterion)) {
            for (SearchParameters.FilterCriteria criteria : criterion) {
                processSearchAttributes(structType, criteria, indexFiltered, graphFiltered, allAttributes);
            }
        } else if (StringUtils.isNotEmpty(filterCriteria.getAttributeName())) {
            try {
                String      attributeName = filterCriteria.getAttributeName();
                String      qualifiedName = structType.getQualifiedAttributeName(attributeName);
                Set<String> indexedKeys   = context.getIndexedKeys();

                if (indexedKeys != null && indexedKeys.contains(qualifiedName)) {
                    indexFiltered.add(attributeName);
                } else {
                    LOG.warn("search includes non-indexed attribute '{}'; might cause poor performance", qualifiedName);

                    graphFiltered.add(attributeName);
                }

                if (structType instanceof AtlasEntityType) {
                    // Capture the entity attributes
                    context.getEntityAttributes().add(attributeName);
                }

                allAttributes.add(attributeName);
            } catch (AtlasBaseException e) {
                LOG.warn(e.getMessage());
            }
        }
    }

    //
    // If filterCriteria contains any non-indexed attribute inside OR condition:
    //    Index+Graph can't be used. Need to use only Graph query filter for all attributes. Examples:
    //    (OR idx-att1=x non-idx-attr=z)
    //    (AND idx-att1=x (OR idx-attr2=y non-idx-attr=z))
    // Else
    //    Index query can be used for indexed-attribute filtering and Graph query for non-indexed attributes. Examples:
    //      (AND idx-att1=x idx-attr2=y non-idx-attr=z)
    //      (AND (OR idx-att1=x idx-attr1=y) non-idx-attr=z)
    //      (AND (OR idx-att1=x idx-attr1=y) non-idx-attr=z (AND idx-attr2=xyz idx-attr2=abc))
    //
    protected boolean canApplyIndexFilter(AtlasStructType structType, FilterCriteria filterCriteria, boolean insideOrCondition) {
        if (filterCriteria == null) {
            return true;
        }

        boolean              ret             = true;
        Condition            filterCondition = filterCriteria.getCondition();
        List<FilterCriteria> criterion       = filterCriteria.getCriterion();
        Set<String>          indexedKeys     = context.getIndexedKeys();


        if (filterCondition != null && CollectionUtils.isNotEmpty(criterion)) {
            insideOrCondition = insideOrCondition || filterCondition == Condition.OR;

            // If we have nested criterion let's find any nested ORs with non-indexed attr
            for (FilterCriteria criteria : criterion) {
                ret = canApplyIndexFilter(structType, criteria, insideOrCondition);

                if (!ret) {
                    break;
                }
            }
        } else if (StringUtils.isNotEmpty(filterCriteria.getAttributeName())) {
            try {
                String qualifiedName = structType.getQualifiedAttributeName(filterCriteria.getAttributeName());

                if (insideOrCondition && (indexedKeys == null || !indexedKeys.contains(qualifiedName))) {
                    ret = false;
                }
            } catch (AtlasBaseException e) {
                LOG.warn(e.getMessage());
            }
        }

        return ret;
    }

    protected void constructTypeTestQuery(StringBuilder indexQuery, String typeAndAllSubTypesQryStr) {
        if (StringUtils.isNotEmpty(typeAndAllSubTypesQryStr)) {
            if (indexQuery.length() > 0) {
                indexQuery.append(AND_STR);
            }

            indexQuery.append("v.\"").append(Constants.TYPE_NAME_PROPERTY_KEY).append("\":").append(typeAndAllSubTypesQryStr);
        }
    }

    protected void constructFilterQuery(StringBuilder indexQuery, AtlasStructType type, FilterCriteria filterCriteria, Set<String> indexAttributes) {
        if (filterCriteria != null) {
            LOG.debug("Processing Filters");

            String filterQuery = toIndexQuery(type, filterCriteria, indexAttributes, 0);

            if (StringUtils.isNotEmpty(filterQuery)) {
                if (indexQuery.length() > 0) {
                    indexQuery.append(AND_STR);
                }

                indexQuery.append(filterQuery);
            }
        }
    }

    protected void constructGremlinFilterQuery(StringBuilder gremlinQuery, Map<String, Object> queryBindings, AtlasStructType structType, FilterCriteria filterCriteria) {
        if (filterCriteria != null) {
            FilterCriteria.Condition condition = filterCriteria.getCondition();

            if (condition != null) {
                StringBuilder orQuery = new StringBuilder();

                List<FilterCriteria> criterion = filterCriteria.getCriterion();

                for (int i = 0; i < criterion.size(); i++) {
                    FilterCriteria criteria = criterion.get(i);

                    if (condition == FilterCriteria.Condition.OR) {
                        StringBuilder nestedOrQuery = new StringBuilder("_()");

                        constructGremlinFilterQuery(nestedOrQuery, queryBindings, structType, criteria);

                        orQuery.append(i == 0 ? "" : ",").append(nestedOrQuery);
                    } else {
                        constructGremlinFilterQuery(gremlinQuery, queryBindings, structType, criteria);
                    }
                }

                if (condition == FilterCriteria.Condition.OR) {
                    gremlinQuery.append(".or(").append(orQuery).append(")");
                }
            } else {
                String         attributeName = filterCriteria.getAttributeName();
                AtlasAttribute attribute     = structType.getAttribute(attributeName);

                if (attribute != null) {
                    SearchParameters.Operator operator       = filterCriteria.getOperator();
                    String                    attributeValue = filterCriteria.getAttributeValue();

                    gremlinQuery.append(toGremlinComparisonQuery(attribute, operator, attributeValue, queryBindings));
                } else {
                    LOG.warn("Ignoring unknown attribute {}.{}", structType.getTypeName(), attributeName);
                }

            }
        }
    }

    protected void constructStateTestQuery(StringBuilder indexQuery) {
        if (indexQuery.length() > 0) {
            indexQuery.append(AND_STR);
        }

        indexQuery.append("v.\"").append(Constants.STATE_PROPERTY_KEY).append("\":ACTIVE");
    }

    private String toIndexQuery(AtlasStructType type, FilterCriteria criteria, Set<String> indexAttributes, int level) {
        return toIndexQuery(type, criteria, indexAttributes, new StringBuilder(), level);
    }

    private String toIndexQuery(AtlasStructType type, FilterCriteria criteria, Set<String> indexAttributes, StringBuilder sb, int level) {
        if (criteria.getCondition() != null && CollectionUtils.isNotEmpty(criteria.getCriterion())) {
            StringBuilder nestedExpression = new StringBuilder();

            for (FilterCriteria filterCriteria : criteria.getCriterion()) {
                String nestedQuery = toIndexQuery(type, filterCriteria, indexAttributes, level + 1);

                if (StringUtils.isNotEmpty(nestedQuery)) {
                    if (nestedExpression.length() > 0) {
                        nestedExpression.append(SPACE_STRING).append(criteria.getCondition()).append(SPACE_STRING);
                    }
                    // todo: when a neq operation is nested and occurs in the beginning of the query, index query has issues
                    nestedExpression.append(nestedQuery);
                }
            }

            if (level == 0) {
                return nestedExpression.length() > 0 ? sb.append(nestedExpression).toString() : EMPTY_STRING;
            } else {
                return nestedExpression.length() > 0 ? sb.append(BRACE_OPEN_STR).append(nestedExpression).append(BRACE_CLOSE_STR).toString() : EMPTY_STRING;
            }
        } else if (indexAttributes.contains(criteria.getAttributeName())){
            return toIndexExpression(type, criteria.getAttributeName(), criteria.getOperator(), criteria.getAttributeValue());
        } else {
            return EMPTY_STRING;
        }
    }

    private String toIndexExpression(AtlasStructType type, String attrName, SearchParameters.Operator op, String attrVal) {
        String ret = EMPTY_STRING;

        try {
            if (OPERATOR_MAP.get(op) != null) {
                String qualifiedName = type.getQualifiedAttributeName(attrName);

                ret = String.format(OPERATOR_MAP.get(op), qualifiedName, AtlasStructType.AtlasAttribute.escapeIndexQueryValue(attrVal));
            }
        } catch (AtlasBaseException ex) {
            LOG.warn(ex.getMessage());
        }

        return ret;
    }

    protected AtlasGraphQuery toGraphFilterQuery(AtlasStructType type, FilterCriteria criteria, Set<String> graphAttributes, AtlasGraphQuery query) {
        if (criteria != null) {
            if (criteria.getCondition() != null) {
                if (criteria.getCondition() == Condition.AND) {
                    for (FilterCriteria filterCriteria : criteria.getCriterion()) {
                        AtlasGraphQuery nestedQuery = toGraphFilterQuery(type, filterCriteria, graphAttributes, context.getGraph().query());

                        query.addConditionsFrom(nestedQuery);
                    }
                } else {
                    List<AtlasGraphQuery> orConditions = new LinkedList<>();

                    for (FilterCriteria filterCriteria : criteria.getCriterion()) {
                        AtlasGraphQuery nestedQuery = toGraphFilterQuery(type, filterCriteria, graphAttributes, context.getGraph().query());

                        orConditions.add(context.getGraph().query().createChildQuery().addConditionsFrom(nestedQuery));
                    }

                    if (!orConditions.isEmpty()) {
                        query.or(orConditions);
                    }
                }
            } else if (graphAttributes.contains(criteria.getAttributeName())) {
                String                    attrName  = criteria.getAttributeName();
                String                    attrValue = criteria.getAttributeValue();
                SearchParameters.Operator operator  = criteria.getOperator();

                try {
                    final String qualifiedName = type.getQualifiedAttributeName(attrName);

                    switch (operator) {
                        case LT:
                            query.has(qualifiedName, AtlasGraphQuery.ComparisionOperator.LESS_THAN, attrValue);
                            break;
                        case LTE:
                            query.has(qualifiedName, AtlasGraphQuery.ComparisionOperator.LESS_THAN_EQUAL, attrValue);
                            break;
                        case GT:
                            query.has(qualifiedName, AtlasGraphQuery.ComparisionOperator.GREATER_THAN, attrValue);
                            break;
                        case GTE:
                            query.has(qualifiedName, AtlasGraphQuery.ComparisionOperator.GREATER_THAN_EQUAL, attrValue);
                            break;
                        case EQ:
                            query.has(qualifiedName, AtlasGraphQuery.ComparisionOperator.EQUAL, attrValue);
                            break;
                        case NEQ:
                            query.has(qualifiedName, AtlasGraphQuery.ComparisionOperator.NOT_EQUAL, attrValue);
                            break;
                        case LIKE:
                            // TODO: Maybe we need to validate pattern
                            query.has(qualifiedName, AtlasGraphQuery.MatchingOperator.REGEX, getLikeRegex(attrValue));
                            break;
                        case CONTAINS:
                            query.has(qualifiedName, AtlasGraphQuery.MatchingOperator.REGEX, getContainsRegex(attrValue));
                            break;
                        case STARTS_WITH:
                            query.has(qualifiedName, AtlasGraphQuery.MatchingOperator.PREFIX, attrValue);
                            break;
                        case ENDS_WITH:
                            query.has(qualifiedName, AtlasGraphQuery.MatchingOperator.REGEX, getSuffixRegex(attrValue));
                            break;
                        case IN:
                            LOG.warn("{}: unsupported operator. Ignored", operator);
                            break;
                    }
                } catch (AtlasBaseException e) {
                    LOG.error("toGraphFilterQuery(): failed for attrName=" + attrName + "; operator=" + operator + "; attrValue=" + attrValue, e);
                }
            }
        }

        return query;
    }

    private String toGremlinComparisonQuery(AtlasAttribute attribute, SearchParameters.Operator operator, String attrValue, Map<String, Object> queryBindings) {
        String bindName  = "__bind_" + queryBindings.size();
        Object bindValue = attribute.getAttributeType().getNormalizedValue(attrValue);

        AtlasGremlinQueryProvider queryProvider = AtlasGremlinQueryProvider.INSTANCE;
        String queryTemplate = null;
        switch (operator) {
            case LT:
                queryTemplate = queryProvider.getQuery(AtlasGremlinQueryProvider.AtlasGremlinQuery.COMPARE_LT);
                break;
            case GT:
                queryTemplate = queryProvider.getQuery(AtlasGremlinQueryProvider.AtlasGremlinQuery.COMPARE_GT);
                break;
            case LTE:
                queryTemplate = queryProvider.getQuery(AtlasGremlinQueryProvider.AtlasGremlinQuery.COMPARE_LTE);
                break;
            case GTE:
                queryTemplate = queryProvider.getQuery(AtlasGremlinQueryProvider.AtlasGremlinQuery.COMPARE_GTE);
                break;
            case EQ:
                queryTemplate = queryProvider.getQuery(AtlasGremlinQueryProvider.AtlasGremlinQuery.COMPARE_EQ);
                break;
            case NEQ:
                queryTemplate = queryProvider.getQuery(AtlasGremlinQueryProvider.AtlasGremlinQuery.COMPARE_NEQ);
                break;
            case LIKE:
                queryTemplate = queryProvider.getQuery(AtlasGremlinQueryProvider.AtlasGremlinQuery.COMPARE_MATCHES);
                break;
            case STARTS_WITH:
                queryTemplate = queryProvider.getQuery(AtlasGremlinQueryProvider.AtlasGremlinQuery.COMPARE_STARTS_WITH);
                break;
            case ENDS_WITH:
                queryTemplate = queryProvider.getQuery(AtlasGremlinQueryProvider.AtlasGremlinQuery.COMPARE_ENDS_WITH);
                break;
            case CONTAINS:
                queryTemplate = queryProvider.getQuery(AtlasGremlinQueryProvider.AtlasGremlinQuery.COMPARE_CONTAINS);
                break;
        }

        if (org.apache.commons.lang3.StringUtils.isNotEmpty(queryTemplate)) {
            if (bindValue instanceof Date) {
                bindValue = ((Date)bindValue).getTime();
            }

            queryBindings.put(bindName, bindValue);

            return String.format(queryTemplate, attribute.getQualifiedName(), bindName);
        } else {
            return EMPTY_STRING;
        }
    }

    private String getContainsRegex(String attributeValue) {
        return ".*" + attributeValue + ".*";
    }

    private String getSuffixRegex(String attributeValue) {
        return ".*" + attributeValue;
    }

    private String getLikeRegex(String attributeValue) { return ".*" + attributeValue + ".*"; }

    protected List<AtlasVertex> getVerticesFromIndexQueryResult(Iterator<AtlasIndexQuery.Result> idxQueryResult, List<AtlasVertex> vertices) {
        if (idxQueryResult != null) {
            while (idxQueryResult.hasNext()) {
                AtlasVertex vertex = idxQueryResult.next().getVertex();

                vertices.add(vertex);
            }
        }

        return vertices;
    }

    protected List<AtlasVertex> getVertices(Iterator<AtlasVertex> iterator, List<AtlasVertex> vertices) {
        if (iterator != null) {
            while (iterator.hasNext()) {
                AtlasVertex vertex = iterator.next();

                vertices.add(vertex);
            }
        }

        return vertices;
    }

    protected Set<String> getGuids(List<AtlasVertex> vertices) {
        Set<String> ret = new HashSet<>();

        if (vertices != null) {
            for(AtlasVertex vertex : vertices) {
                String guid = AtlasGraphUtilsV1.getIdFromVertex(vertex);

                if (StringUtils.isNotEmpty(guid)) {
                    ret.add(guid);
                }
            }
        }

        return ret;
    }

    private static int getApplicationProperty(String propertyName, int defaultValue) {
        try {
            return ApplicationProperties.get().getInt(propertyName, defaultValue);
        } catch (AtlasException excp) {
            // ignore
        }

        return defaultValue;
    }
}
