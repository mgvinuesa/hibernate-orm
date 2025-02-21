/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.sqm.sql.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.hibernate.metamodel.MappingMetamodel;
import org.hibernate.metamodel.mapping.EmbeddableValuedModelPart;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.ManagedMappingType;
import org.hibernate.metamodel.mapping.ModelPartContainer;
import org.hibernate.metamodel.model.domain.EntityDomainType;
import org.hibernate.query.sqm.sql.SqmToSqlAstConverter;
import org.hibernate.query.sqm.tree.domain.SqmEmbeddedValuedSimplePath;
import org.hibernate.query.sqm.tree.domain.SqmPath;
import org.hibernate.query.sqm.tree.domain.SqmTreatedPath;
import org.hibernate.spi.NavigablePath;
import org.hibernate.sql.ast.SqlAstWalker;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.SqlTuple;
import org.hibernate.sql.ast.tree.expression.SqlTupleContainer;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.ast.tree.update.Assignable;

import static org.hibernate.query.sqm.internal.SqmUtil.needsTargetTableMapping;

/**
 * @author Steve Ebersole
 */
public class EmbeddableValuedPathInterpretation<T> extends AbstractSqmPathInterpretation<T> implements Assignable, SqlTupleContainer {

	/**
	 * Static factory
	 */
	public static <T> Expression from(
			SqmEmbeddedValuedSimplePath<T> sqmPath,
			SqmToSqlAstConverter sqlAstCreationState,
			boolean jpaQueryComplianceEnabled) {
		final SqmPath<?> lhs = sqmPath.getLhs();
		final TableGroup tableGroup = sqlAstCreationState.getFromClauseAccess().getTableGroup( lhs.getNavigablePath() );
		EntityMappingType treatTarget = null;
		if ( jpaQueryComplianceEnabled ) {
			final MappingMetamodel mappingMetamodel = sqlAstCreationState.getCreationContext()
					.getSessionFactory()
					.getRuntimeMetamodels()
					.getMappingMetamodel();
			if ( lhs instanceof SqmTreatedPath ) {
				//noinspection rawtypes
				final EntityDomainType<?> treatTargetDomainType = ( (SqmTreatedPath) lhs ).getTreatTarget();
				treatTarget = mappingMetamodel.findEntityDescriptor( treatTargetDomainType.getHibernateEntityName() );
			}
			else if ( lhs.getNodeType() instanceof EntityDomainType ) {
				//noinspection rawtypes
				final EntityDomainType<?> entityDomainType = (EntityDomainType) lhs.getNodeType();
				treatTarget = mappingMetamodel.findEntityDescriptor( entityDomainType.getHibernateEntityName() );

			}
		}

		final ModelPartContainer modelPartContainer = tableGroup.getModelPart();
		final EmbeddableValuedModelPart mapping;
		if ( needsTargetTableMapping( sqmPath, modelPartContainer, sqlAstCreationState ) ) {
			// In the select, group by, order by and having clause we have to make sure we render
			// the column of the target table, never the FK column, if the lhs is a join type that
			// requires it (right, full) or if this path is contained in group by clause
			mapping = (EmbeddableValuedModelPart) ( (ManagedMappingType) modelPartContainer.getPartMappingType() ).findSubPart(
					sqmPath.getReferencedPathSource().getPathName(),
					treatTarget
			);
		}
		else {
			mapping = (EmbeddableValuedModelPart) modelPartContainer.findSubPart(
					sqmPath.getReferencedPathSource().getPathName(),
					treatTarget
			);
		}

		return new EmbeddableValuedPathInterpretation<>(
				mapping.toSqlExpression(
						tableGroup,
						sqlAstCreationState.getCurrentClauseStack().getCurrent(),
						sqlAstCreationState,
						sqlAstCreationState
				),
				sqmPath.getNavigablePath(),
				mapping,
				tableGroup
		);
	}

	private final SqlTuple sqlExpression;

	public EmbeddableValuedPathInterpretation(
			SqlTuple sqlExpression,
			NavigablePath navigablePath,
			EmbeddableValuedModelPart mapping,
			TableGroup tableGroup) {
		super( navigablePath, mapping, tableGroup );
		this.sqlExpression = sqlExpression;
	}

	@Override
	public SqlTuple getSqlExpression() {
		return sqlExpression;
	}

	@Override
	public void accept(SqlAstWalker sqlTreeWalker) {
		sqlExpression.accept( sqlTreeWalker );
	}

	@Override
	public String toString() {
		return "EmbeddableValuedPathInterpretation(" + getNavigablePath() + ")";
	}

	@Override
	public void visitColumnReferences(Consumer<ColumnReference> columnReferenceConsumer) {
		for ( Expression expression : sqlExpression.getExpressions() ) {
			if ( !( expression instanceof ColumnReference ) ) {
				throw new IllegalArgumentException( "Expecting ColumnReference, found : " + expression );
			}
			columnReferenceConsumer.accept( (ColumnReference) expression );
		}
	}

	@Override
	public List<ColumnReference> getColumnReferences() {
		final List<ColumnReference> results = new ArrayList<>();
		visitColumnReferences( results::add );
		return results;
	}

	@Override
	public SqlTuple getSqlTuple() {
		return sqlExpression;
	}
}
