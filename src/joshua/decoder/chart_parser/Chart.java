/* This file is part of the Joshua Machine Translation System.
 * 
 * Joshua is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free
 * Software Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */
package joshua.decoder.chart_parser;


import joshua.corpus.vocab.SymbolTable;
import joshua.decoder.JoshuaConfiguration;
import joshua.decoder.chart_parser.Bin.ComputeItemResult;
import joshua.decoder.chart_parser.DotChart.DotItem;
import joshua.decoder.ff.FeatureFunction;
import joshua.decoder.ff.tm.Grammar;
import joshua.decoder.ff.tm.Rule;
import joshua.decoder.ff.tm.RuleCollection;
import joshua.decoder.ff.tm.Trie;
import joshua.decoder.hypergraph.HGNode;
import joshua.decoder.hypergraph.HyperGraph;
import joshua.decoder.segment_file.ConstraintRule;
import joshua.decoder.segment_file.ConstraintSpan;
import joshua.lattice.Lattice;
import joshua.lattice.Arc;
import joshua.lattice.Node;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Chart class this class implements chart-parsing:
 * (1) seeding the chart 
 * (2) cky main loop over bins, 
 * (3) identify applicable rules in each bin
 * Note: the combination operation will be done in Bin
 * 
 * Signatures of class:
 * Bin: i, j
 * SuperItem (used for CKY check): i,j, lhs
 * Item ("or" node): i,j, lhs, edge ngrams
 * Deduction ("and" node)
 * 
 * index of sentences: start from zero
 * index of cell: cell (i,j) represent span of words indexed [i,j-1]
 * where i is in [0,n-1] and j is in [1,n]
 *
 * @author Zhifei Li, <zhifei.work@gmail.com>
 * @version $LastChangedDate$
 */
public class Chart {
	
	public  Grammar[]        grammars;
	public  DotChart[]       dotcharts;//each grammar should have a dotchart associated with it
	public  Bin[][]          bins;//note that in some cell, it might be null
	private Bin              goal_bin;
	
	private Lattice<Integer> sentence;//a list of foreign words
	//private int[] sentence;
	public  int              sent_len;//foreign sent len
	private int              sent_id;
	
	//decoder-wide variables
	ArrayList<FeatureFunction> p_l_models;
	
	/**
	 * Shared symbol table for source language terminals, target
	 * language terminals, and shared nonterminals.
	 * <p>
	 * It may be that separate tables should be maintained for
	 * the source and target languages.
	 * <p>
	 * This class adds an untranslated word ID to the symbol
	 * table. The Bin class adds a goal symbol nonterminal to
	 * the symbol table.
	 * <p>
	 */
	SymbolTable p_symbolTable;
	
	//statistics
	int gtem                  = 0;
	int n_prepruned           = 0;//how many items have been pruned away because its cost is greater than the cutoff in calling chart.add_deduction_in_chart()
	int n_prepruned_fuzz1     = 0;
	int n_prepruned_fuzz2     = 0;
	int n_pruned              = 0;
	int n_merged              = 0;
	int n_added               = 0;
	int n_dotitem_added       = 0;//note: there is no pruning in dot-item
	int n_called_compute_item = 0;
	
	//time-profile variables, debug purpose
	long        g_time_compute_item      = 0;
	long        g_time_add_deduction     = 0;
	static long g_time_lm                = 0;
	static long g_time_score_sent        = 0;
	static long g_time_check_nonterminal = 0;
	static long g_time_kbest_extract     = 0;
	
	private static final Logger logger = 
		Logger.getLogger(Chart.class.getName());
	
	int goalSymbolID = -1;
	
	HashMap<String,ConstraintSpan> tblConstraintSpansForFiltering; // TODO: each span only has one ConstraintSpan
	ArrayList<Span> listOfSpansWithHardRuleConstraint; //contain spans that have hard "rule" constraint; key: start_span; value: end_span
	
	public Chart(
		Lattice<Integer>           sentence,
		ArrayList<FeatureFunction> models,
		SymbolTable                symbolTable,
		int                        sent_id,
		Grammar[]                  grammars,
		boolean                    have_lm_model,
		String                     goalSymbol,
		List<ConstraintSpan> constraintSpans)
	{	
		this.sentence = sentence;
		this.sent_len = sentence.size() - 1;
		this.p_l_models = models;
		this.p_symbolTable = symbolTable;
		
		// TODO: this is very expensive
		this.bins     = new Bin[sent_len][sent_len+1];
		this.sent_id  = sent_id;
		this.goalSymbolID = this.p_symbolTable.addNonterminal(goalSymbol);
		this.goal_bin = new Bin(this, this.goalSymbolID);
		
		// add un-translated words into the chart as item (with large cost)
		// TODO: grammar specific?
		this.grammars = grammars;
		
		// each grammar will have a dot chart
		this.dotcharts = new DotChart[this.grammars.length];
		for (int i = 0; i < this.grammars.length; i++) {
			this.dotcharts[i] = new DotChart(this.sentence, this.grammars[i], this);
			this.dotcharts[i].seed(); // TODO: should fold into the constructor
		}
		
	
		
		/**
		 * (1) add manual rule (only allow flat rules) into the
		 *     chart as constraints
		 * (2) add RHS or LHS constraint into
		 *     tblConstraintSpansForFiltering
		 * (3) add span signature into setOfSpansWithHardRuleConstraint; if the span contains a hard "RULE" constraint
		 */
		if (null != constraintSpans) {
			
			for (ConstraintSpan cSpan : constraintSpans) {
				if (null != cSpan.rules()) {
					boolean shouldAdd = false; // contain LHS or RHS constraints?
					for (ConstraintRule cRule : cSpan.rules()) {
						
						// TODO: prefer switch for enum instead of ==
						if (cRule.type() == ConstraintRule.Type.RULE) {
							
							// force the feature cost as zero
							float[] featureScores = new float[cRule.features().length];//TODO: this require the input always specify the right number of features
							for (int i = 0; i < featureScores.length; i++) {
								if (cSpan.isHard())
									featureScores[i] = 0;
								else
									featureScores[i] = cRule.features()[i];
							}
							
							if (cSpan.isHard()){
								if(listOfSpansWithHardRuleConstraint==null)
									listOfSpansWithHardRuleConstraint = new ArrayList<Span>();
								listOfSpansWithHardRuleConstraint.add(new Span(cSpan.start(), cSpan.end()));
								System.out.println("add hard rule constraint in span " +cSpan.start() +", " + cSpan.end());
							}
							//TODO: which grammar should we use to create a mannual rule?
							int arity = 0; // only allow flat rule (i.e. arity=0)
							Rule rule = this.grammars[0].constructManualRule(
									symbolTable.addNonterminal(cRule.lhs()), 
									symbolTable.addTerminals(cRule.foreignRhs()),
									symbolTable.addTerminals(cRule.nativeRhs()),
									featureScores, 
									arity);
							add_axiom(cSpan.start(), cSpan.end(), rule, 0);
						} else {
							shouldAdd = true;
						}
					}
					if (shouldAdd) {
						if(tblConstraintSpansForFiltering == null )
							tblConstraintSpansForFiltering = new HashMap<String, ConstraintSpan>();
						tblConstraintSpansForFiltering.put(getSpanSignature(cSpan.start(), cSpan.end()), cSpan);
					}
				}
			}
		}
		
		// add OOV rules; this should be called after the manual constraints have been set up
		// TODO: the transition cost for phrase model, arity penalty, word penalty are all zero, except the LM cost
		for (Node<Integer> node : sentence) {
			for (Arc<Integer> arc : node.getOutgoingArcs()) {
				// create a rule, but do not add into the grammar trie
				// TODO: which grammar should we use to create an OOV rule?
				Rule rule = this.grammars[0].constructOOVRule(p_l_models.size(), 
						arc.getLabel(), have_lm_model);
				
				// tail and head are switched - FIX names:
				if(isContainHardRuleConstraint( node.getNumber(), arc.getTail().getNumber()) ){
					//do not add the oov axiom
					System.out.println("having hard rule constraint in span " +node.getNumber() +", " + arc.getTail().getNumber());
				}else{
					add_axiom(node.getNumber(), arc.getTail().getNumber(), rule, (float)arc.getCost());
				}
			}
		}
		
		if (logger.isLoggable(Level.FINE))
			logger.fine("Finished seeding chart.");
	}
	
	private class Span{
		int startPos;
		int endPos;
		public Span(int startPos_, int endPos_){
			startPos= startPos_;
			endPos = endPos_;
		}
	}
	
	private boolean isContainHardRuleConstraint(int startSpan, int endSpan){
		if(listOfSpansWithHardRuleConstraint!=null){
			for(Span span : listOfSpansWithHardRuleConstraint){
				if(startSpan >= span.startPos && endSpan <= span.endPos)
					return true;
			}			
		}
		return false;
	}
	
	/**
	 * Construct the hypergraph with the help from DotChart.
	 */
	public HyperGraph expand() {
//		long start = System.currentTimeMillis();
//		long time_step1 = 0;
//		long time_step2 = 0;
//		long time_step3 = 0;
//		long time_step4 = 0;
		
		for (int width = 1; width <= sent_len; width++) {
			for (int i = 0; i <= sent_len-width; i++) {
				int j = i + width;
				if (logger.isLoggable(Level.FINEST)) 
					logger.finest(String.format("Process span (%d, %d)",i,j));
				
				//(1)### expand the cell in dotchart
				//long start_step1= Support.current_time();
				if (logger.isLoggable(Level.FINEST))
					logger.finest("Expanding cell");
				for (int k = 0; k < this.grammars.length; k++) {
					this.dotcharts[k].expand_cell(i,j);
				}			
				if (logger.isLoggable(Level.FINEST)) 
					logger.finest(String.format("n_dotitem= %d", n_dotitem_added));
				//time_step1 += Support.current_time()-start_step1;
				
				//(2)### populate COMPLETE rules into Chart: the regular CKY part
				//long start_step2= Support.current_time();
				if (logger.isLoggable(Level.FINEST))
					logger.finest("Adding complete items into chart");
				for (int k = 0; k < this.grammars.length; k++) {
					if (this.grammars[k].hasRuleForSpan(i, j, sent_len)
					&& null != this.dotcharts[k].l_dot_bins[i][j]) {
						
						for (DotItem dt: this.dotcharts[k].l_dot_bins[i][j].l_dot_items) {
							float lattice_cost = dt.lattice_cost;
							RuleCollection rules = dt.tnode.getRules();
							
							logger.finest("Checking DotItem for matched rules.");
							
							if (null != rules) { // have rules under this trienode
								// TODO: filter the rule according to LHS constraint								
								if (logger.isLoggable(Level.FINEST)) {
									for (Rule r : rules.getSortedRules()) {
										logger.finest("Matched [" + i + ", " +  
												j + "] with " + p_symbolTable.getWord(r.getLHS()) +
												" => " + p_symbolTable.getWords(r.getFrench()) + 
												" | " + p_symbolTable.getWords(r.getEnglish()));
									}
								}
								
								if (rules.getArity() == 0) { // rules without any non-terminal
									add_axioms(i, j, rules, lattice_cost);
								} else { // rules with non-terminal
									if (JoshuaConfiguration.use_cube_prune) {
										complete_cell_cube_prune(i, j, dt, rules, lattice_cost);
									} else {
										// populate chart.bin[i][j] with rules from dotchart[i][j]
										complete_cell(i, j, dt, rules, lattice_cost);
									}
								}
							}
						}
					}
				}
				//time_step2 += Support.current_time()-start_step2;
				
				//(3)### process unary rules (e.g., S->X, NP->NN), just add these items in chart, assume acyclic
				//long start_step3= Support.current_time();
				if (logger.isLoggable(Level.FINEST))
					logger.finest("Adding unary items into chart");
				for (int k = 0; k < this.grammars.length; k++) {
					if (this.grammars[k].hasRuleForSpan(i, j, sent_len)) {
						add_unary_items(this.grammars[k],i,j);//single-branch path
					}
				}
				//time_step3 += Support.current_time()-start_step3;
				
				//(4)### in dot_cell(i,j), add dot-items that start from the /complete/ superIterms in chart_cell(i,j)
				//long start_step4= Support.current_time();
				if (logger.isLoggable(Level.FINEST))
					logger.finest("Initializing new dot-items that start from complete items in this cell");
				for (int k = 0; k < this.grammars.length; k++) {
					if (this.grammars[k].hasRuleForSpan(i, j, sent_len)) {
						this.dotcharts[k].start_dotitems(i,j);
					}
				}
				//time_step4 += Support.current_time()-start_step4;
				
				//(5)### sort the items in the cell: for pruning purpose
				if (logger.isLoggable(Level.FINEST)) 
					logger.finest(String.format(
						"After Process span (%d, %d), called:= %d",
						i, j, n_called_compute_item));
				if (null != this.bins[i][j]) {
					// this.bins[i][j].print_info(Support.INFO);

					// this is required
					@SuppressWarnings("unused")
					ArrayList<HGNode> l_s_its = this.bins[i][j].get_sorted_items();
					
					/*
					// sanity check with this cell
					int sum_d = 0;
					double sum_c = 0.0;
					double sum_total=0.0;
					for (Item t_item : l_s_its) {
						if (null != t_item.l_deductions)
							sum_d += t_item.l_deductions.size();
						sum_c += t_item.best_deduction.best_cost;
						sum_total += t_item.est_total_cost;
					}
					*/
				}
			}
		}
		print_info(Level.FINE);
		logger.info("Sentence length: " + sent_len);
		// transition_final: setup a goal item, which may have many deductions
		if (null != this.bins[0][sent_len]) {
			goal_bin.transit_to_goal(this.bins[0][sent_len]); // update goal_bin				
		} else {
			throw new RuntimeException(
				"No complete item in the cell(0,n); possible reasons: " +
				"(1) your grammar does not have any valid derivation for the source sentence; " +
				"(2) two aggressive pruning");
		}
		
		//debug purpose
		//long sec_consumed = (System.currentTimeMillis() -start)/1000;
		//logger.info("######Expand time consumption: "+ sec_consumed);
		//logger.info(String.format("Step1: %d; step2: %d; step3: %d; step4: %d", time_step1, time_step2, time_step3, time_step4));
		
		/*logger.info(String.format("t_compute_item: %d; t_add_deduction: %d;", g_time_compute_item / 1000, g_time_add_deduction / 1000));
		for (FeatureFunction m: this.models) {
			logger.info("FeatureFunction cost: " + m.time_consumed/1000);
		}*/

		//logger.info(String.format("t_lm: %d; t_score_lm: %d; t_check_nonterminal: %d", g_time_lm, g_time_score_sent, g_time_check_nonterminal));
		//LMModel tm_lm = (LMModel)this.models.get(0);
		//logger.info(String.format("LM lookupwords1, step1: %d; step2: %d; step3: %d", tm_lm.time_step1, tm_lm.time_step2, tm_lm.time_step3));
		//debug end
		
		return new HyperGraph(goal_bin.get_sorted_items().get(0), -1, -1, sent_id, sent_len); // num_items/deductions : -1
	}
	
	public void print_info(Level level) {
		if (logger.isLoggable(level)) {
			logger.log(level,
				String.format("ADDED: %d; MERGED: %d; PRUNED: %d; PRE-PRUNED: %d, FUZZ1: %d, FUZZ2: %d; DOT-ITEMS ADDED: %d",
					this.n_added,
					this.n_merged,
					this.n_pruned,
					this.n_prepruned,
					this.n_prepruned_fuzz1,
					this.n_prepruned_fuzz2,
					this.n_dotitem_added));
		}
	}
	
	/**
	 * agenda based extension: this is necessary in case more
	 * than two unary rules can be applied in topological order
	 * s->x; ss->s for unary rules like s->x, once x is complete,
	 * then s is also complete
	 */
	private int add_unary_items(Grammar gr, int i, int j) {
		Bin chart_bin = this.bins[i][j];
		if (null == chart_bin) {
			return 0;
		}
		int count_of_additions_to_t_queue = 0;
		ArrayList<HGNode> t_queue
			= new ArrayList<HGNode>(chart_bin.get_sorted_items());
		
		
		while (t_queue.size() > 0) {
			HGNode item = t_queue.remove(0);
			Trie child_tnode = gr.getTrieRoot().matchOne(item.lhs); // match rule and complete part
			if (child_tnode != null
			&& child_tnode.getRules() != null
			&& child_tnode.getRules().getArity() == 1) { // have unary rules under this trienode
				ArrayList<HGNode> l_ants = new ArrayList<HGNode>();
				l_ants.add(item);
				List<Rule> l_rules = child_tnode.getRules().getSortedRules();
				
				for (Rule rule : l_rules) { // for each unary rules								
					ComputeItemResult tbl_states = chart_bin.compute_item(rule, l_ants, i, j);
					HGNode res_item = chart_bin.add_deduction_in_bin(tbl_states, rule, i, j, l_ants, 0.0f);
					if (null != res_item) {
						t_queue.add(res_item);
						count_of_additions_to_t_queue++;
					}
				}
			}
		}
		return count_of_additions_to_t_queue;
	}
	
	
	private void add_axioms(int i, int j, RuleCollection rb, float lattice_cost) {
		if(isContainHardRuleConstraint( i, j )){
			System.out.println("having hard rule constraint in span " +i +", " + j);
			return; //do not add any axioms
		}
		
		
		List<Rule> l_rules = filterRules(i,j, rb.getSortedRules());
		for (Rule rule : l_rules) {
			add_axiom(i, j, rule, lattice_cost);
		}
	}
	/** axiom is for rules with zero-arity */
	private void add_axiom(int i, int j, Rule rule, float lattice_cost) {
		if (null == this.bins[i][j]) {
			this.bins[i][j] = new Bin(this, this.goalSymbolID);
		}
		this.bins[i][j].add_axiom(i, j, rule, lattice_cost);
	}
	
	
	private void complete_cell(int i, int j, DotItem dt, RuleCollection rb, float lattice_cost) {
		if(isContainHardRuleConstraint( i, j )){
			System.out.println("having hard rule constraint in span " +i +", " + j);
			return; //do not add any axioms
		}
		
		if (null == this.bins[i][j]) {
			this.bins[i][j] = new Bin(this, this.goalSymbolID);
		}
		// combinations: rules, antecent items
		this.bins[i][j].complete_cell(i, j, dt.l_ant_super_items, filterRules(i,j,rb.getSortedRules()), rb.getArity(), lattice_cost);
	}
	
	
	private void complete_cell_cube_prune(int i, int j, DotItem dt,	RuleCollection rb,	float lattice_cost) {
		if(isContainHardRuleConstraint( i, j )){
			System.out.println("having hard rule constraints in span " +i +", " + j);
			return; //do not add any axioms
		}
		
		if (null == this.bins[i][j]) {
			this.bins[i][j] = new Bin(this, this.goalSymbolID);
		}
		
		this.bins[i][j].complete_cell_cube_prune(i, j, dt.l_ant_super_items, filterRules(i,j, rb.getSortedRules()), lattice_cost);//combinations: rules, antecent items
	}
	
	private String getSpanSignature(int i, int j) {
		return i + " " + j;
	}
	
	/** if there are any LHS or RHS constraints for a span, 
	 * then all the applicable grammar rules in that span will have to pass the filter
	 * */
	private List<Rule> filterRules(int i, int j, List<Rule> rulesIn) {
		if (null == tblConstraintSpansForFiltering)
			return rulesIn;
		
		ConstraintSpan cSpan = tblConstraintSpansForFiltering.get( getSpanSignature(i,j));
		if (null == cSpan) { // no filtering
			return rulesIn;
		} else {
			List<Rule> rulesOut = new ArrayList<Rule>();
			for (Rule gRule : rulesIn) {
				//gRule will survive, if any constraint (LHS or RHS) lets it survive 
				for (ConstraintRule cRule : cSpan.rules()) {
					if (shouldSurvive(cRule, gRule)) {
						rulesOut.add(gRule);
						break;
					}
				}
			}
			//System.out.println("beging to look for " + i + " " + j + "; out size:" + rulesOut.size()+  "; input size: " + rulesIn.size());
			return rulesOut;
		}
	}
	
	
	//should we filter out the gRule based on the manually provided constraint cRule
	private boolean shouldSurvive(ConstraintRule cRule, Rule gRule) {
		
		switch (cRule.type()) {
		case LHS:
			return (gRule.getLHS() == p_symbolTable.addNonterminal(cRule.lhs()) );
		case RHS:
			int[] targetWords = p_symbolTable.addTerminals(cRule.nativeRhs());
			
			if (targetWords.length != gRule.getEnglish().length)
				return false;
			
			for (int t = 0; t < targetWords.length; t++) {
				if (targetWords[t] != gRule.getEnglish()[t])
					return false;
			}
			
			return true;
		default: // not surviving
			return false;
		}
	}
}
