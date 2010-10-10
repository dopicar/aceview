/*
 * This file is part of ACE View.
 * Copyright 2008-2009, Attempto Group, University of Zurich (see http://attempto.ifi.uzh.ch).
 *
 * ACE View is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * ACE View is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with ACE View.
 * If not, see http://www.gnu.org/licenses/.
 */

package ch.uzh.ifi.attempto.aceview;

import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.event.OWLModelManagerChangeEvent;
import org.protege.editor.owl.model.event.OWLModelManagerListener;
import org.protege.editor.owl.ui.OWLWorkspaceViewsTab;
import org.protege.editor.owl.ui.renderer.OWLEntityRenderer;
import org.protege.editor.owl.ui.renderer.OWLEntityRendererListener;
import org.semanticweb.owlapi.io.OWLRendererException;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationSubject;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLAxiomChange;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLLogicalAxiom;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyChangeException;
import org.semanticweb.owlapi.model.OWLOntologyChangeListener;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import ch.uzh.ifi.attempto.aceview.lexicon.MorphType;
import ch.uzh.ifi.attempto.aceview.lexicon.TokenMapper;
import ch.uzh.ifi.attempto.aceview.model.event.EventType;
import ch.uzh.ifi.attempto.aceview.util.OntologyUtils;
import ch.uzh.ifi.attempto.aceview.util.Showing;

/**
 * <p>The ACE View tab monitors several Protege events and generates
 * corresponding ACE View events.</p>
 * 
 * @author Kaarel Kaljurand
 */
public class ACEViewTab extends OWLWorkspaceViewsTab {

	private static final Logger logger = Logger.getLogger(ACEViewTab.class);

	// Fired when ontologies are loaded/created and reasoner is run
	private final OWLModelManagerListener modelManagerListener = new OWLModelManagerListener() {
		public void handleChange(OWLModelManagerChangeEvent event) {
			if (event.isType(org.protege.editor.owl.model.event.EventType.ACTIVE_ONTOLOGY_CHANGED)) {
				ACETextManager.setActiveACETextIRI(getOWLModelManager().getActiveOntology().getOntologyID());
			}
			else if (event.isType(org.protege.editor.owl.model.event.EventType.ONTOLOGY_LOADED)) {
				ACETextManager.createACEText(getOWLModelManager().getActiveOntology().getOntologyID());
				try {
					initACEText();
					ACETextManager.fireEvent(EventType.ACETEXT_LOADED);
				} catch (Exception e) {
					logger.error(e.getMessage());
				}
			}
			else if (event.isType(org.protege.editor.owl.model.event.EventType.ONTOLOGY_VISIBILITY_CHANGED)) {
				logger.info("ONTOLOGY_VISIBILITY_CHANGED");
			}
			else if (event.isType(org.protege.editor.owl.model.event.EventType.ONTOLOGY_CLASSIFIED)) {
				logger.info("ONTOLOGY_CLASSIFIED");
				if (ACEViewPreferences.getInstance().isUpdateAnswersOnClassify()) {
					new UpdateAnswersUI(null, ACETextManager.getActiveACEText(), getOWLModelManager()).updateAnswers();
				}
			}
			else if (event.isType(org.protege.editor.owl.model.event.EventType.ONTOLOGY_CREATED)) {
				// If an ontology is created then this ontology is set as active, therefore
				// we can get the URI of this ontology by asking for the URI of the active ontology.
				ACETextManager.createACEText(getOWLModelManager().getActiveOntology().getOntologyID());
				ACETextManager.fireEvent(EventType.ACETEXT_CREATED);
			}
			else {
				logger.info("OWLModelManagerChangeEvent: " + event.getType());
			}
		}
	};


	// Fired when axioms are added and removed
	private final OWLOntologyChangeListener ontologyChangeListener = new OWLOntologyChangeListener() {
		public void ontologiesChanged(List<? extends OWLOntologyChange> changes) {
			processChanges(getOWLModelManager(), changes);
		}
	};


	// Fired when entity rendering is changed (e.g. the label-annotation in modified)
	private final OWLEntityRendererListener entityRendererListener = new OWLEntityRendererListener() {
		public void renderingChanged(OWLEntity entity, OWLEntityRenderer renderer) {
			String entityRendering = renderer.render(entity);
			logger.info("Rendering for " + entity + " changed to " + entityRendering);

			OWLModelManager mm = getOWLModelManager();
			OWLOntology ont = mm.getActiveOntology();
			OWLOntologyManager ontologyManager = mm.getOWLOntologyManager();

			List<OWLAxiomChange> changeList1 = removeMorfAnnotations(ont, entity);
			List<OWLAxiomChange> changeList2 = addMorfAnnotations(mm.getOWLDataFactory(), ont, entity, entityRendering);
			changeList1.addAll(changeList2);

			OntologyUtils.changeOntology(ontologyManager, changeList1);
		}
	};


	@Override
	public void initialise() {
		super.initialise();

		try {
			ParserHolder.updateACEParser(ACEViewPreferences.getInstance());
		} catch (Exception e) {
			logger.error(e.getMessage());
		}

		ACETextManager.setOWLModelManager(getOWLModelManager());
		ACETextManager.createACEText(getOWLModelManager().getActiveOntology().getOntologyID());
		// Note: We start to listen before filling the ACE text, because
		// we want to add entity annotations to the lexicon.
		getOWLModelManager().addOntologyChangeListener(ontologyChangeListener);
		try {
			initACEText();
		} catch (Exception e) {
			logger.error(e.getMessage());
		}
		getOWLModelManager().getOWLEntityRenderer().addListener(entityRendererListener);
		getOWLModelManager().addListener(modelManagerListener);
	}


	@Override
	public void dispose() {
		getOWLModelManager().removeOntologyChangeListener(ontologyChangeListener);
		getOWLModelManager().removeListener(modelManagerListener);
		getOWLModelManager().getOWLEntityRenderer().removeListener(entityRendererListener);
		super.dispose();
	}


	/**
	 * <p>This method is called either when the ACE View tab is launched, or
	 * when the ONTOLOGY_LOADED even is fired. In both cases the ACE text
	 * must be generated that corresponds to the ontology.</p>
	 * 
	 * @throws OWLRendererException
	 * @throws OWLOntologyCreationException
	 * @throws OWLOntologyChangeException
	 */
	private static void initACEText() throws OWLRendererException, OWLOntologyCreationException, OWLOntologyChangeException {
		OWLModelManager mm = ACETextManager.getOWLModelManager();
		OWLOntologyManager ontologyManager = mm.getOWLOntologyManager();
		OWLDataFactory df = mm.getOWLDataFactory();
		ACEViewPreferences prefs = ACEViewPreferences.getInstance();
		Set<OWLOntology> ontologies = mm.getOntologies();

		logger.info("Init: ontologies: " + ontologies);

		ACETextManager.setInitCompleted(false);
		for (OWLOntology ont : ontologies) {
			initACETextFromOntology(ontologyManager, df, prefs, ont);
		}
		ACETextManager.setInitCompleted(true);
	}


	private static void initACETextFromOntology(OWLOntologyManager ontologyManager, OWLDataFactory df, ACEViewPreferences prefs, OWLOntology ont) throws OWLRendererException, OWLOntologyCreationException, OWLOntologyChangeException {
		OWLOntologyID iri = ont.getOntologyID();
		Set<OWLEntity> entities = ont.getSignature();

		logger.info("Init: ontology " + iri + " contains " + entities.size() + " referenced entities");

		// For each entity:
		// add morphological annotations (CN_sg, CN_pl, TV_sg, TV_pl, TV_vbg, PN_sg) to the ontology.
		List<AddAxiomByACEView> changes = Lists.newArrayList();
		for (OWLEntity entity : entities) {
			if (Showing.isShow(entity)) {
				Set<OWLAnnotationAssertionAxiom> annSet = MorphAnnotation.getMorphAnnotations(df, ont, entity);
				logger.info("Init: entity " + entity + " adding annotations: " + annSet);
				for (OWLAnnotationAssertionAxiom ax : annSet) {
					changes.add(new AddAxiomByACEView(ont, ax));
				}
			}
		}
		OntologyUtils.changeOntology(ontologyManager, changes);


		// For each OWLAnnotationAssertionAxiom,
		// add a corresponding entry to the lexicon.
		ACEText<OWLEntity, OWLLogicalAxiom> acetext = ACETextManager.getACEText(iri);
		Set<OWLAnnotationAssertionAxiom> annotationAxioms = ont.getAxioms(AxiomType.ANNOTATION_ASSERTION);

		for (OWLAnnotationAssertionAxiom annotationAxiom : annotationAxioms) {
			addToLexicon(annotationAxiom, acetext.getTokenMapper());
		}


		// Translate every OWL logical axiom into ACE snippet and add it to the ACE text.
		// If the logical axiom is already annotated with an ACE snippet then add the snippet instead.
		AxiomVerbalizer axiomVerbalizer = new AxiomVerbalizer(prefs.getOwlToAce());

		for (OWLLogicalAxiom logicalAxiom : ont.getLogicalAxioms()) {
			Set<OWLAnnotation> existingAnnotations = logicalAxiom.getAnnotations();
			logger.info("Init: Add axiom: " + logicalAxiom + " (" + existingAnnotations.size() + " annotations)");
			processAxiom(ont, df, ontologyManager, acetext, axiomVerbalizer, iri, logicalAxiom, existingAnnotations);
		}
	}


	/**
	 * <p>Monitors all the changes that happen in the model and reacts to them,
	 * e.g. updates the ACE text. Only axiom changes (additions, removals) are handled.
	 * The respective axiom can be a logical or an annotation axiom.</p>
	 * 
	 * @param changes List of ontology changes
	 */
	private static void processChanges(OWLModelManager mngr, List<? extends OWLOntologyChange> changes) {
		OWLDataFactory df = mngr.getOWLDataFactory();
		OWLOntologyManager ontologyManager = mngr.getOWLOntologyManager();
		ACEViewPreferences prefs = ACEViewPreferences.getInstance();

		// Counts axioms that cause the ACE text to change.
		int textAxiomCounter = 0;
		// Counts axioms that cause the ACE lexicon to change.
		int lexiconAxiomCounter = 0;

		for (OWLOntologyChange change : changes) {

			if (! change.isAxiomChange()) {
				logger.warn("Not handling change: " + change.getClass());
				continue;
			}

			OWLOntology changeOnt = change.getOntology();
			OWLOntologyID oid = changeOnt.getOntologyID();
			OWLAxiom axiom = change.getAxiom();
			ACEText<OWLEntity, OWLLogicalAxiom> acetext = ACETextManager.getACEText(oid);
			TokenMapper acelexicon = acetext.getTokenMapper();

			if (axiom instanceof OWLLogicalAxiom) {

				OWLLogicalAxiom logicalAxiom = (OWLLogicalAxiom) axiom;

				if (change instanceof AddAxiomByACEView) {
					// If an axiom was added in the "ACE view" then do nothing. We don't even have
					// to annotate the entities because such axioms cannot introduce new entities,
					// i.e. sentences that contain words which are not covered by the lexicon (i.e.
					// entity annotations) are not sent to the parser at all, they are rejected before that.
					continue;
				}

				else if (change instanceof RemoveAxiomByACEView) {
					continue;
				}

				else if (change instanceof AddAxiom) {
					textAxiomCounter++;
					Set<OWLAnnotation> existingAnnotations = logicalAxiom.getAnnotations();
					Set<OWLEntity> entities = ((OWLAxiomChange) change).getEntities();
					logger.info("Add axiom: " + logicalAxiom + " (" + existingAnnotations.size() + " annotations)" + " (" + entities.size() + " entities)");
					try {
						AxiomVerbalizer axiomVerbalizer = new AxiomVerbalizer(prefs.getOwlToAce());
						processAxiom(changeOnt, df, ontologyManager, acetext, axiomVerbalizer, oid, logicalAxiom, existingAnnotations);
					} catch (Exception e) {
						logger.error(e.getMessage());
					}
				}
				else if (change instanceof RemoveAxiom) {
					textAxiomCounter++;
					logger.info("Del axiom: " + logicalAxiom);
					Set<OWLLogicalAxiom> tanglingAxioms = acetext.removeAxiom(logicalAxiom);
					ACETextManager.processTanglingAxioms(acetext, tanglingAxioms);
				}
				else {
					logger.warn("AxiomChange was neither addition nor removal: " + change.getClass());
				}
			}
			else if (axiom instanceof OWLAnnotationAssertionAxiom) {

				logger.info("Processing: " + axiom);
				OWLAnnotationAssertionAxiom annAx = (OWLAnnotationAssertionAxiom) axiom;
				OWLAnnotationSubject subject = annAx.getSubject();
				if (subject instanceof IRI) {
					IRI annotationIRI = annAx.getProperty().getIRI();

					if (MorphType.isMorphTypeIRI(annotationIRI)) {

						String annValue = getAnnotationValueAsString(annAx.getValue());

						if (annValue == null) {
							// The annotation value is not a constant.
							logger.error("Malformed ACE lexicon annotation ignored: " + annAx);
						}
						else {
							lexiconAxiomCounter++;
							if (change instanceof AddAxiom) {
								acelexicon.addEntry(annValue, (IRI) subject, annotationIRI);
							}
							else if (change instanceof RemoveAxiom) {
								acelexicon.removeEntry(annValue, (IRI) subject, annotationIRI);
							}
						}
					}
				}
			}
			else if (axiom instanceof OWLDeclarationAxiom) {
				OWLDeclarationAxiom declarationAxiom = (OWLDeclarationAxiom) axiom;

				if (change instanceof AddAxiom) {
					OWLEntity entity = declarationAxiom.getEntity();
					logger.info("Add declaration axiom: " + entity);
					Set<OWLAnnotationAssertionAxiom> morphAnnotations = MorphAnnotation.getMorphAnnotations(df, changeOnt, entity);
					logger.info("Triggered: add: " + morphAnnotations);
					ACETextManager.addAxiomsToOntology(ontologyManager, changeOnt, morphAnnotations);
				}
				else if (change instanceof RemoveAxiom) {
					logger.info("Del declaration axiom: " + declarationAxiom);
					logger.warn("NOT IMPLEMENTED");
					// TODO: BUG: not implemented
				}
			}
			else {
				// logger.warn("Not handling axiom change for " + axiom.getClass());
			}
		}

		if (textAxiomCounter > 0) {
			ACETextManager.fireEvent(EventType.ACETEXT_CHANGED);
		}
		if (lexiconAxiomCounter > 0) {
			ACETextManager.fireEvent(EventType.ACELEXICON_CHANGED);
		}
	}


	/**
	 * <p>Processes a logical axiom that has been added by Protege, either
	 * via the GUI or when loading an ontology.
	 * The input axiom undergoes the following processing.</p>
	 * 
	 * <ol>
	 *  <li>Does it contain any ACE annotations (i.e. has this axiom been already ACEified)?
	 *  <ol>
	 *   <li>If yes then convert the annotations (there can be more than one)
	 *   into ACE snippets and add the snippets to the ACE text</li>
	 *   <li>If not then
	 *   <ol>
	 *    <li>verbalize the axiom</li>
	 *    <li>add the verbalization to the ACE text</li>
	 *    <li>add the verbalization as an annotation to the axiom</li>
	 *    <li>put the new annotation axiom into the ontology</li>
	 *   </ol>
	 *   </li>
	 *  </ol>
	 * </li>
	 * </ul>
	 * 
	 * @param ont
	 * @param df
	 * @param ontologyManager
	 * @param acetext
	 * @param axiomVerbalizer
	 * @param ns
	 * @param logicalAxiom
	 * @param existingAnnotations
	 * @throws OWLRendererException
	 * @throws OWLOntologyCreationException
	 * @throws OWLOntologyChangeException
	 */
	private static void processAxiom(OWLOntology ont, OWLDataFactory df, OWLOntologyManager ontologyManager, ACEText acetext, AxiomVerbalizer axiomVerbalizer, OWLOntologyID ns, OWLLogicalAxiom logicalAxiom, Set<OWLAnnotation> existingAnnotations) throws OWLRendererException, OWLOntologyCreationException, OWLOntologyChangeException {

		ACESnippet newSnippet = null;

		for (OWLAnnotation annotation : existingAnnotations) {
			if (annotation.getProperty().getIRI().equals(ACETextManager.acetextIRI)) {
				String aceAnnotationValue = getAnnotationValueAsString(annotation.getValue());
				if (aceAnnotationValue == null) {
					logger.error("Malformed ACE annotation ignored: " + annotation);
				}
				else {
					logger.info("ACE annotation: " + aceAnnotationValue);
					newSnippet = new ACESnippetImpl(ns, aceAnnotationValue, logicalAxiom);
					acetext.add(newSnippet);
				}
			}
		}

		if (newSnippet == null) {
			newSnippet = axiomVerbalizer.verbalizeAxiom(ont, logicalAxiom);
			if (newSnippet == null) {
				logger.warn("AxiomVerbalizer produced a null-snippet for: " + logicalAxiom);
			}
			else {
				addToText(newSnippet, ont, df, ontologyManager, acetext, logicalAxiom);
			}
		}

		ACETextManager.setSelectedSnippet(newSnippet);


		// If we have a OWLInverseObjectPropertiesAxiom where both properties
		// are named then we set the vbg-annotation of each property to contain
		// the name of the other property. This way the verbalization comes out nicer, e.g.
		// Jupiter is-larger-than every planet that is not Jupiter.
		// could be paraphrased as
		// Every planet that is not Jupiter is-smaller-than Jupiter.
		// BUG: this won't really work because
		// 1. the verbalizer adds "is/are ... by" around the form
		// 2. the lexicon might not like 'is-larger-than' used both as sg and vbg (in different entries)
		// TODO: This if-sentence is currently switched off.
		if (false && logicalAxiom instanceof OWLInverseObjectPropertiesAxiom) {
			OWLInverseObjectPropertiesAxiom iopa = (OWLInverseObjectPropertiesAxiom) logicalAxiom;
			if (! iopa.getFirstProperty().isAnonymous() && ! iopa.getSecondProperty().isAnonymous()) {
				OWLObjectProperty p1 = iopa.getFirstProperty().asOWLObjectProperty();
				OWLObjectProperty p2 = iopa.getSecondProperty().asOWLObjectProperty();

				// BUG: removes all, not just the vbg-annotations
				OntologyUtils.changeOntology(ontologyManager,
						ACETextManager.getRemoveChanges(ont, p1.getAnnotationAssertionAxioms(ont)));
				Set<OWLAxiom> set = Sets.newHashSet();
				set.add(getTV_vbg(df, p1, p2));
				set.add(getTV_vbg(df, p2, p1));
				ACETextManager.addAxiomsToOntology(ontologyManager, ont, set);
			}
		}
	}

	/**
	 * 
	 * @param addedSnippet
	 * @param ont
	 * @param df
	 * @param ontologyManager
	 * @param acetext
	 * @param logicalAxiom
	 */
	/*
	private static void addToText(ACESnippet snippet, OWLOntology ont, OWLDataFactory df, OWLOntologyManager ontologyManager, ACEText acetext, OWLLogicalAxiom logicalAxiom) {
		acetext.add(snippet);
		OWLAnnotation ann = df.getOWLConstantAnnotation(ACETextManager.acetextURI, df.getOWLUntypedConstant(snippet.toString()));
		OWLAxiomAnnotationAxiom axannax = df.getOWLAxiomAnnotationAxiom(logicalAxiom, ann);
		ACETextManager.addAxiomsToOntology(ontologyManager, ont, Sets.newHashSet(axannax));
	}
	 */

	/**
	 * TODO: ACE annotations are currently not added
	 */
	private static void addToText(ACESnippet snippet, OWLOntology ont, OWLDataFactory df, OWLOntologyManager ontologyManager, ACEText acetext, OWLLogicalAxiom logicalAxiom) {
		acetext.add(snippet);
		// Create and apply the changeset with the following changes:
		// 1. Remove the logical axiom
		// 2. Add the logical axiom with the ACE text as annotation
	}


	private static String getAnnotationValueAsString(OWLAnnotationValue value) {
		if (value instanceof OWLLiteral) {
			return ((OWLLiteral) value).getLiteral();
		}
		return value.toString();
	}


	/**
	 * 
	 * @param df
	 * @param p1
	 * @param p2
	 * @return A single <code>OWLEntityAnnotationAxiom</code>
	 */
	private static OWLAnnotationAssertionAxiom getTV_vbg(OWLDataFactory df, OWLObjectProperty p1, OWLObjectProperty p2) {
		String vbgForm = p2.getIRI().getFragment();
		// TODO: use the rendering instead
		//String vbgForm = getOWLModelManager().getRendering(p2);
		return OntologyUtils.createEntityAnnotationAxiom(df, MorphType.TV_VBG.getIRI(), p1, vbgForm);
	}


	private static List<OWLAxiomChange> addMorfAnnotations(OWLDataFactory df, OWLOntology ont, OWLEntity entity, String lemma) {
		List<OWLAxiomChange> addList = Lists.newArrayList();
		Set<OWLAnnotationAssertionAxiom> entityAnnotationAxioms = MorphAnnotation.createMorphAnnotations(df, entity, lemma);
		if (entityAnnotationAxioms.isEmpty()) {
			logger.info("Init: entity " + entity + " is already annotated");
		}
		else {
			logger.info("Init: entity " + entity + " adding annotations: " + entityAnnotationAxioms);
			for (OWLAnnotationAssertionAxiom ax : entityAnnotationAxioms) {
				addList.add(new AddAxiom(ont, ax));
			}
		}
		return addList;
	}


	/**
	 * <p>Removes all the morph. annotation axioms that apply to
	 * the IRI of the given entity.</p>
	 * 
	 * @param ont
	 * @param entity
	 * @return
	 */
	private static List<OWLAxiomChange> removeMorfAnnotations(OWLOntology ont, OWLEntity entity) {
		List<OWLAxiomChange> removeList = Lists.newArrayList();
		for (OWLAnnotationAssertionAxiom ax: entity.getAnnotationAssertionAxioms(ont)) {
			if (MorphType.isMorphTypeIRI(ax.getProperty().getIRI())) {
				removeList.add(new RemoveAxiom(ont, ax));
			}
		}

		return removeList;
	}


	/**
	 * <p>Adds an entry to the lexicon (token mapper), on the basis
	 * of the given annotation axiom.</p>
	 * 
	 * @param annAx
	 * @param tokenMapper
	 */
	private static void addToLexicon(OWLAnnotationAssertionAxiom annAx, TokenMapper tokenMapper) {
		logger.info("Processing: " + annAx);
		OWLAnnotationSubject subject = annAx.getSubject();
		if (subject instanceof IRI) {
			IRI annotationIRI = annAx.getProperty().getIRI();
			if (MorphType.isMorphTypeIRI(annotationIRI)) {
				String annValue = getAnnotationValueAsString(annAx.getValue());
				if (annValue == null) {
					logger.error("Malformed ACE lexicon annotation ignored: " + annAx);
				}
				else {
					tokenMapper.addEntry(annValue, (IRI) subject, annotationIRI);
				}
			}
		}
	}
}