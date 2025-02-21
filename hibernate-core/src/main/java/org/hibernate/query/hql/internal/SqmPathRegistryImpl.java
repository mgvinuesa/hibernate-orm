/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.hql.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.hibernate.jpa.spi.JpaCompliance;
import org.hibernate.metamodel.model.domain.BasicDomainType;
import org.hibernate.metamodel.model.domain.spi.JpaMetamodelImplementor;
import org.hibernate.query.hql.HqlLogging;
import org.hibernate.query.hql.spi.SqmCreationProcessingState;
import org.hibernate.query.hql.spi.SqmPathRegistry;
import org.hibernate.query.sqm.AliasCollisionException;
import org.hibernate.query.sqm.ParsingException;
import org.hibernate.query.sqm.SqmPathSource;
import org.hibernate.query.sqm.SqmTreeCreationLogger;
import org.hibernate.query.sqm.tree.domain.SqmPath;
import org.hibernate.query.sqm.tree.from.SqmCrossJoin;
import org.hibernate.query.sqm.tree.from.SqmEntityJoin;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmRoot;
import org.hibernate.query.sqm.tree.select.SqmAliasedNode;
import org.hibernate.query.sqm.tree.select.SqmSubQuery;
import org.hibernate.spi.NavigablePath;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;

/**
 * Container for indexing needed while building an SQM tree.
 *
 * @author Steve Ebersole
 */
public class SqmPathRegistryImpl implements SqmPathRegistry {
	private final SqmCreationProcessingState associatedProcessingState;
	private final JpaCompliance jpaCompliance;

	private final Map<NavigablePath, SqmPath<?>> sqmPathByPath = new HashMap<>();
	private final Map<NavigablePath, SqmFrom<?, ?>> sqmFromByPath = new HashMap<>();

	private final Map<String, SqmFrom<?, ?>> sqmFromByAlias = new HashMap<>();

	private final List<SqmAliasedNode<?>> simpleSelectionNodes = new ArrayList<>();

	public SqmPathRegistryImpl(SqmCreationProcessingState associatedProcessingState) {
		this.associatedProcessingState = associatedProcessingState;
		this.jpaCompliance = associatedProcessingState.getCreationState().getCreationContext().getJpaMetamodel().getJpaCompliance();
	}

	@Override
	public void register(SqmPath<?> sqmPath) {
		SqmTreeCreationLogger.LOGGER.tracef( "SqmProcessingIndex#register(SqmPath) : %s", sqmPath.getNavigablePath() );

		// Generally we:
		//		1) add the path to the path-by-path map
		//		2) if the path is a from, we add it to the from-by-path map
		//		3) if the path is a from and defines an alias, we add it to the from-by-alias map
		//
		// Regarding part #1 (add to the path-by-path map), it is ok for a SqmFrom to replace a
		// 		non-SqmFrom.  This should equate to, e.g., an implicit join.

		if ( sqmPath instanceof SqmFrom<?, ?> ) {
			final SqmFrom<?, ?> sqmFrom = (SqmFrom<?, ?>) sqmPath;

			final String alias = sqmPath.getExplicitAlias();
			if ( alias != null ) {
				final String aliasToUse = jpaCompliance.isJpaQueryComplianceEnabled()
						? alias.toLowerCase( Locale.getDefault() )
						: alias;

				final SqmFrom<?, ?> previousFrom = sqmFromByAlias.put( aliasToUse, sqmFrom );

				if ( previousFrom != null ) {
					throw new AliasCollisionException(
							String.format(
									Locale.ENGLISH,
									"Alias [%s] used for multiple from-clause elements : %s, %s",
									alias,
									previousFrom,
									sqmPath
							)
					);
				}
			}

			final SqmFrom<?, ?> previousFromByPath = sqmFromByPath.put( sqmPath.getNavigablePath(), sqmFrom );

			if ( previousFromByPath != null ) {
				// this should never happen
				throw new ParsingException(
						String.format(
								Locale.ROOT,
								"Registration for SqmFrom [%s] overrode previous registration: %s -> %s",
								sqmPath.getNavigablePath(),
								previousFromByPath,
								sqmFrom
						)
				);
			}
		}

		final SqmPath<?> previousPath = sqmPathByPath.put( sqmPath.getNavigablePath(), sqmPath );

		if ( previousPath instanceof SqmFrom ) {
			// this should never happen
			throw new ParsingException(
					String.format(
							Locale.ROOT,
							"Registration for path [%s] overrode previous registration: %s -> %s",
							sqmPath.getNavigablePath(),
							previousPath,
							sqmPath
					)
			);
		}
	}

	@Override
	public <E> void replace(SqmEntityJoin<E> sqmJoin, SqmRoot<E> sqmRoot) {
		final String alias = sqmJoin.getExplicitAlias();
		if ( alias != null ) {
			final String aliasToUse = jpaCompliance.isJpaQueryComplianceEnabled()
					? alias.toLowerCase( Locale.getDefault() )
					: alias;

			final SqmFrom<?, ?> previousFrom = sqmFromByAlias.put( aliasToUse, sqmJoin );
			if ( previousFrom != null && !( previousFrom instanceof SqmRoot ) ) {
				throw new AliasCollisionException(
						String.format(
								Locale.ENGLISH,
								"Alias [%s] used for multiple from-clause elements : %s, %s",
								alias,
								previousFrom,
								sqmJoin
						)
				);
			}
		}

		final SqmFrom<?, ?> previousFromByPath = sqmFromByPath.put( sqmJoin.getNavigablePath(), sqmJoin );
		if ( previousFromByPath != null && !( previousFromByPath instanceof SqmRoot ) ) {
			// this should never happen
			throw new ParsingException(
					String.format(
							Locale.ROOT,
							"Registration for SqmFrom [%s] overrode previous registration: %s -> %s",
							sqmJoin.getNavigablePath(),
							previousFromByPath,
							sqmJoin
					)
			);
		}

		final SqmPath<?> previousPath = sqmPathByPath.put( sqmJoin.getNavigablePath(), sqmJoin );
		if ( previousPath instanceof SqmFrom && !( previousPath instanceof SqmRoot ) ) {
			// this should never happen
			throw new ParsingException(
					String.format(
							Locale.ROOT,
							"Registration for path [%s] overrode previous registration: %s -> %s",
							sqmJoin.getNavigablePath(),
							previousPath,
							sqmJoin
					)
			);
		}
	}

	@Override
	public <X extends SqmFrom<?, ?>> X findFromByPath(NavigablePath navigablePath) {
		//noinspection unchecked
		return (X) sqmFromByPath.get( navigablePath );
	}

	@Override
	public <X extends SqmFrom<?, ?>> X findFromByAlias(String alias, boolean searchParent) {
		final String localAlias = jpaCompliance.isJpaQueryComplianceEnabled()
				? alias.toLowerCase( Locale.getDefault() )
				: alias;

		final SqmFrom<?, ?> registered = sqmFromByAlias.get( localAlias );

		if ( registered != null ) {
			//noinspection unchecked
			return (X) registered;
		}

		SqmCreationProcessingState parentProcessingState = associatedProcessingState.getParentProcessingState();
		if ( searchParent && parentProcessingState != null ) {
			X parentRegistered;
			do {
				parentRegistered = parentProcessingState.getPathRegistry().findFromByAlias(
						alias,
						false
				);
				parentProcessingState = parentProcessingState.getParentProcessingState();
			} while (parentProcessingState != null && parentRegistered == null);
			if ( parentRegistered != null ) {
				// If a parent query contains the alias, we need to create a correlation on the subquery
				final SqmSubQuery<?> selectQuery = ( SqmSubQuery<?> ) associatedProcessingState.getProcessingQuery();
				SqmFrom<?, ?> correlated;
				if ( parentRegistered instanceof Root<?> ) {
					correlated = selectQuery.correlate( (Root<?>) parentRegistered );
				}
				else if ( parentRegistered instanceof Join<?, ?> ) {
					correlated = selectQuery.correlate( (Join<?, ?>) parentRegistered );
				}
				else if ( parentRegistered instanceof SqmCrossJoin<?> ) {
					correlated = selectQuery.correlate( (SqmCrossJoin<?>) parentRegistered );
				}
				else if ( parentRegistered instanceof SqmEntityJoin<?> ) {
					correlated = selectQuery.correlate( (SqmEntityJoin<?>) parentRegistered );
				}
				else {
					throw new UnsupportedOperationException( "Can't correlate from node: " + parentRegistered );
				}
				register( correlated );
				//noinspection unchecked
				return (X) correlated;
			}
		}

		return null;
	}

	@Override
	public <X extends SqmFrom<?, ?>> X findFromExposing(String navigableName) {
		// todo (6.0) : atm this checks every from-element every time, the idea being to make sure there
		//  	is only one such element obviously that scales poorly across larger from-clauses.  Another
		//  	(configurable?) option would be to simply pick the first one as a perf optimization

		SqmFrom<?, ?> found = null;
		for ( Map.Entry<NavigablePath, SqmFrom<?, ?>> entry : sqmFromByPath.entrySet() ) {
			final SqmFrom<?, ?> fromElement = entry.getValue();
			if ( definesAttribute( fromElement.getReferencedPathSource(), navigableName ) ) {
				if ( found != null ) {
					throw new IllegalStateException( "Multiple from-elements expose unqualified attribute : " + navigableName );
				}
				found = fromElement;
			}
		}

		if ( found == null ) {
			if ( associatedProcessingState.getParentProcessingState() != null ) {
				HqlLogging.QUERY_LOGGER.debugf(
						"Unable to resolve unqualified attribute [%s] in local from-clause; checking parent ",
						navigableName
				);
				found = associatedProcessingState.getParentProcessingState().getPathRegistry().findFromExposing( navigableName );
			}
		}

		HqlLogging.QUERY_LOGGER.debugf(
				"Unable to resolve unqualified attribute [%s] in local from-clause",
				navigableName
		);

		//noinspection unchecked
		return (X) found;
	}

	@Override
	public <X extends SqmFrom<?, ?>> X resolveFrom(NavigablePath navigablePath, Function<NavigablePath, SqmFrom<?, ?>> creator) {
		SqmTreeCreationLogger.LOGGER.tracef( "SqmProcessingIndex#resolvePath(NavigablePath) : %s", navigablePath );

		final SqmFrom<?, ?> existing = sqmFromByPath.get( navigablePath );
		if ( existing != null ) {
			//noinspection unchecked
			return (X) existing;
		}

		final SqmFrom<?, ?> sqmFrom = creator.apply( navigablePath );
		register( sqmFrom );
		//noinspection unchecked
		return (X) sqmFrom;
	}

	@Override
	public <X extends SqmFrom<?, ?>> X resolveFrom(SqmPath<?> path) {
		SqmTreeCreationLogger.LOGGER.tracef( "SqmProcessingIndex#resolvePath(SqmPath) : %s", path );

		final SqmFrom<?, ?> existing = sqmFromByPath.get( path.getNavigablePath() );
		if ( existing != null ) {
			//noinspection unchecked
			return (X) existing;
		}

		final SqmFrom<?, ?> sqmFrom = resolveFrom( path.getLhs() ).join( path.getNavigablePath().getLocalName() );
		register( sqmFrom );
		//noinspection unchecked
		return (X) sqmFrom;
	}

	private boolean definesAttribute(SqmPathSource<?> containerType, String name) {
		return !( containerType.getSqmType() instanceof BasicDomainType )
				&& containerType.findSubPathSource( name, getJpaMetamodel() ) != null;
	}

	private JpaMetamodelImplementor getJpaMetamodel() {
		return associatedProcessingState.getCreationState().getCreationContext().getJpaMetamodel();
	}

	@Override
	public SqmAliasedNode<?> findAliasedNodeByAlias(String alias) {
		assert alias != null;

		final String aliasToUse = jpaCompliance.isJpaQueryComplianceEnabled()
				? alias.toLowerCase( Locale.getDefault() )
				: alias;

		for ( int i = 0; i < simpleSelectionNodes.size(); i++ ) {
			final SqmAliasedNode<?> node = simpleSelectionNodes.get( i );
			if ( aliasToUse.equals( node.getAlias() ) ) {
				return node;
			}
		}

		return null;
	}

	@Override
	public Integer findAliasedNodePosition(String alias) {
		if ( alias == null ) {
			return null;
		}

		final String aliasToUse = jpaCompliance.isJpaQueryComplianceEnabled()
				? alias.toLowerCase( Locale.getDefault() )
				: alias;

		// NOTE : 1-based

		for ( int i = 0; i < simpleSelectionNodes.size(); i++ ) {
			final SqmAliasedNode<?> node = simpleSelectionNodes.get( i );
			if ( aliasToUse.equals( node.getAlias() ) ) {
				return i + 1;
			}
		}

		return null;
	}

	@Override
	public SqmAliasedNode<?> findAliasedNodeByPosition(int position) {
		// NOTE : 1-based
		return position > simpleSelectionNodes.size()
				? null
				: simpleSelectionNodes.get(position - 1);
	}

	@Override
	public void register(SqmAliasedNode<?> node) {
		checkResultVariable( node );
		simpleSelectionNodes.add( node );
	}

	private void checkResultVariable(SqmAliasedNode<?> selection) {
		final String alias = selection.getAlias();
		if ( alias == null ) {
			return;
		}

		final Integer position = findAliasedNodePosition( alias );
		if ( position != null ) {
			throw new AliasCollisionException(
					String.format(
							Locale.ENGLISH,
							"Alias [%s] is already used in same select clause [position=%s]",
							alias,
							position
					)
			);
		}
	}
}
