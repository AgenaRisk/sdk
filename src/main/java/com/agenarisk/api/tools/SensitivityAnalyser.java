package com.agenarisk.api.tools;

import com.agenarisk.api.exception.AdapterException;
import com.agenarisk.api.exception.CalculationException;
import com.agenarisk.api.exception.DataSetException;
import com.agenarisk.api.exception.InconsistentEvidenceException;
import com.agenarisk.api.exception.ModelException;
import com.agenarisk.api.exception.NodeException;
import com.agenarisk.api.model.CalculationResult;
import com.agenarisk.api.model.DataSet;
import com.agenarisk.api.model.Model;
import com.agenarisk.api.model.Network;
import com.agenarisk.api.model.Node;
import com.agenarisk.api.model.ResultValue;
import com.agenarisk.api.model.State;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import uk.co.agena.minerva.util.helpers.MathsHelper;
import uk.co.agena.minerva.util.model.IntervalDataPoint;
import uk.co.agena.minerva.util.model.Range;

/**
 *
 * @author Eugene Dementiev
 */
public class SensitivityAnalyser {

	private Model model;
	private Node targetNode;
	private final LinkedHashSet<Node> sensitivityNodes = new LinkedHashSet<>();
	private DataSet dataSet;

	private boolean sumsMean = false;
	private boolean sumsMedian = false;
	private boolean sumsVariance = false;
	private boolean sumsStDev = false;
	private boolean sumsLowerPercentile = false;
	private boolean sumsUpperPercentile = false;

	private double sumsLowerPercentileValue = 25d;
	private double sumsUpperPercentileValue = 75d;

	private double sensLowerPercentileValue = 0d;
	private double sensUpperPercentileValue = 100d;

	/**
	 * Maps nodes to their original calculation results
	 */
	private Map<Node, CalculationResult> bufResultsOriginal = new HashMap<>();
	
	/**
	 * Maps Nodes to their respective calculated SA values
	 */
	private final Map<Node, LinkedHashMap<State, CalculationResult>> bufTargetResultsSubjective = new HashMap<>();

	/**
	 * Maps Nodes to their respective calculated SA values
	 */
	private final Map<Node, LinkedHashMap<BufferedCalculationKey, Double>> bufSACalcs = new HashMap<>();

	/**
	 * Maps Nodes to their respective SA summary stats
	 */
	private final Map<Node, LinkedHashMap<BufferedStatisticKey, Double>> bufSAStats = new HashMap<>();

	/**
	 * Statistics limited within set percentiles. Values outside of percentiles are set to Double.NaN.
	 */
	private final Map<Node, LinkedHashMap<BufferedStatisticKey, Double>> bufSAStatsLim = new HashMap<>();

	/**
	 * Constructor for Sensitivity Analysis tool.<br>
	 * The Model will be factorised and converted to static taking into account any overriding model settings and observations pre-entered into the selected DataSet.<br>
	 * If no DataSet or Network are specified, the first one of each will be used.<br>
	 * 
	 * @param model Model to run analysis on
	 * @param jsonConfig configuration to override defaults for analysis
	 * 
	 * @throws SensitivityAnalyserException upon failure
	 */
	public SensitivityAnalyser(Model model, JSONObject jsonConfig) throws SensitivityAnalyserException {

		if (model == null) {
			throw new SensitivityAnalyserException("Model not provided");
		}

		// Create a copy of the original model
		try {
			model = Model.createModel(model.export(Model.ExportFlags.KEEP_OBSERVATIONS, Model.ExportFlags.KEEP_META));
		}
		catch (AdapterException | JSONException | ModelException ex) {
			throw new SensitivityAnalyserException("Initialization failed", ex);
		}

		// Factorise
		try {
			model.factorize();
		}
		catch (Exception ex) {
			throw new SensitivityAnalyserException("Factorization failed", ex);
		}

		this.model = model;

		// Get model settings
		model.getSettings().fromJson(jsonConfig.optJSONObject("modelSettings"));

		// Get report settings
		JSONObject jsonReportSettings = jsonConfig.optJSONObject("reportSettings");

		if (jsonReportSettings != null) {
			sumsMean = jsonReportSettings.optBoolean("sumsMean", false);
			sumsMedian = jsonReportSettings.optBoolean("sumsMedian", false);
			sumsVariance = jsonReportSettings.optBoolean("sumsVariance", false);
			sumsStDev = jsonReportSettings.optBoolean("sumsStDev", false);
			sumsLowerPercentile = jsonReportSettings.optBoolean("sumsLowerPercentile", false);
			sumsUpperPercentile = jsonReportSettings.optBoolean("sumsUpperPercentile", false);

			sumsLowerPercentileValue = jsonReportSettings.optDouble("sumsLowerPercentileValue", 25d);
			sumsUpperPercentileValue = jsonReportSettings.optDouble("sumsUpperPercentileValue", 75d);

			sensLowerPercentileValue = jsonReportSettings.optDouble("sensLowerPercentileValue", 0d);
			sensUpperPercentileValue = jsonReportSettings.optDouble("sensUpperPercentileValue", 100d);
		}

		// Get DataSet
		if (jsonConfig.has("dataSet")) {
			dataSet = model.getDataSet(jsonConfig.optString("dataSet", ""));
			if (dataSet == null){
				throw new SensitivityAnalyserException("DataSet with id `" + jsonConfig.optString("dataSet", "") + "` not found");
			}
		}
		else {
			dataSet = model.getDataSetList().get(0);
		}
		model.getDataSetList().forEach(ds -> {
			if (!ds.equals(dataSet)) {
				this.model.removeDataSet(ds);
			}
		});

		// Get target Node
		Network network;
		if (jsonConfig.has("network")) {
			network = model.getNetwork(jsonConfig.optString("network", ""));
			if (network == null){
				throw new SensitivityAnalyserException("Network with id `" + jsonConfig.optString("network", "") + "` not found");
			}
		}
		else {
			network = model.getNetworkList().get(0);
		}
		targetNode = network.getNode(jsonConfig.optString("targetNode", ""));

		if (targetNode == null) {
			throw new SensitivityAnalyserException("Target node not specified or Node with ID `" + jsonConfig.optString("targetNode", "") + "`");
		}

		// Get sensitivity nodes
		JSONArray sensitivityNodes = jsonConfig.optJSONArray("sensitivityNodes");
		if (sensitivityNodes != null) {
			try {
				sensitivityNodes.forEach(o -> {
					String nodeId = String.valueOf(o);
					Node sensNode = network.getNode(nodeId);
					if (sensNode == null){
						throw new NodeException("Node with ID `" + nodeId + "` not found in Network " + network.toStringExtra());
					}
					this.sensitivityNodes.add(sensNode);
				});
			}
			catch (NodeException ex){
				throw new SensitivityAnalyserException(ex.getMessage());
			}
		}
		if (this.sensitivityNodes.isEmpty()) {
			throw new SensitivityAnalyserException("No sensitivity nodes specified");
		}

		// Precalculate if required for static conversion
		
		try {
			model.getDataSetList().get(0).getCalculationResults();
		}
		catch (Exception ex){
			// No calculation results, need to calculate
			try {
				model.calculate();
			}
			catch (CalculationException ex1) {
				throw new SensitivityAnalyserException("Failed to precalculate the model during initialization (1)", ex1);
			}
		}
		
		if (!model.isCalculated()) {
			try {
				model.calculate();
			}
			catch (CalculationException ex) {
				throw new SensitivityAnalyserException("Failed to precalculate the model during initialization (2)", ex);
			}
		}
		
		// Convert to static
		try {
			model.convertToStatic(dataSet);
		}
		catch (NodeException ex) {
			throw new SensitivityAnalyserException("Static conversion failed", ex);
		}

		analyse();
		
	}
	
	/**
	 * Performs sensitivity analysis.
	 * 
	 * @throws SensitivityAnalyserException upon failure
	 */
	private void analyse() throws SensitivityAnalyserException {
		calculateCombinations();
		calculateStats();
	}
	
	public JSONObject getReport(){
		JSONObject jsonReport = new JSONObject();
		jsonReport.put("table", buildTables());
		jsonReport.put("tornado", buildTornadoes());
		jsonReport.put("config", getConfig());
		return jsonReport;
	}
	
	public JSONArray buildTables(){
		
		JSONArray jsonTables = new JSONArray();
		
		// Table per sens node
		for(Node sensNode: sensitivityNodes){

			JSONObject jsonTable = new JSONObject();
			jsonTable.put("title", "p(" + targetNode.getName() + " | " + sensNode.getName() + ")");
			jsonTable.put("headerRows", sensNode.getName());
			jsonTable.put("headerColumns", targetNode.getName());
			jsonTable.put("sensitivityNode", sensNode.getId());

			JSONArray jsonRows = new JSONArray();
			jsonTable.put("rows", jsonRows);

			List<State> sensStates = sensNode.getStates();
			
			JSONArray jsonHeaderRow = new JSONArray();
			jsonHeaderRow.put(sensNode.getName() + " State");
			jsonTable.put("headerRow", jsonHeaderRow);
			
			if (targetNode.isNumericInterval()){
				Map<BufferedStatisticKey, Double> bufferedValues = bufSAStats.get(sensNode);
				List<BufferedStatisticKey.STAT> statsRequested = new ArrayList<>();
				if (sumsMean){
					statsRequested.add(BufferedStatisticKey.STAT.mean);
				}
				if (sumsMedian){
					statsRequested.add(BufferedStatisticKey.STAT.median);
				}
				if (sumsVariance){
					statsRequested.add(BufferedStatisticKey.STAT.variance);
				}
				if (sumsStDev){
					statsRequested.add(BufferedStatisticKey.STAT.standardDeviation);
				}
				if (sumsLowerPercentile){
					statsRequested.add(BufferedStatisticKey.STAT.lowerPercentile);
				}
				if (sumsUpperPercentile){
					statsRequested.add(BufferedStatisticKey.STAT.upperPercentile);
				}
				

				// Add column headers
				for(BufferedStatisticKey.STAT statRequested: statsRequested){
					jsonHeaderRow.put(statRequested);
				}
				
				// Row per sens state
				for(State sensState: sensStates){
					JSONArray jsonRow = new JSONArray();
					if (sensNode.isNumericInterval()){
						jsonRow.put(sensState.getLogicState().getNumericalValue());
					}
					else {
						jsonRow.put(sensState.getLabel());
					}
					
					// Column per summary stat
					for(BufferedStatisticKey.STAT statRequested: statsRequested){
						Double value = bufferedValues.get(new BufferedStatisticKey(statRequested, sensState.getLabel()));
						if (Double.isInfinite(value) || Double.isNaN(value)){
							jsonRow.put(value+"");
						}
						else {
							jsonRow.put(value);
						}
						
					}
					jsonRows.put(jsonRow);
				}
			}
			else {
				Map<BufferedCalculationKey, Double> bufferedValues = bufSACalcs.get(sensNode);
				List<State> tarStates = targetNode.getStates();
				
				// Add column headers
				for(State tarState: tarStates){
					jsonHeaderRow.put(tarState.getLabel());
				}
				
				// Row per sens state
				for(State sensState: sensStates){
					JSONArray jsonRow = new JSONArray();
					if (sensNode.isNumericInterval()){
						jsonRow.put(sensState.getLogicState().getNumericalValue());
					}
					else {
						jsonRow.put(sensState.getLabel());
					}
					
					// Column per target state
					for(State tarState: tarStates){
						Double value = bufferedValues.get(new BufferedCalculationKey(targetNode, tarState.getLabel(), sensState.getLabel()));
						jsonRow.put(value);
					}
					jsonRows.put(jsonRow);
				}
				
			}
			
			jsonTables.put(jsonTable);
		}
		
		return jsonTables;
	}
	
	public JSONArray buildTornadoes(){
		
		JSONArray jsonGraphs = new JSONArray();
		
		CalculationResult targetOriginal = bufResultsOriginal.get(targetNode);
		
		if(targetNode.isNumericInterval()){
			/*
				Graphs are created for each selected summary statistic
				For each graph:
					The graph represents stat(targetNode)
					There is a line indicating stat(targetNode) without observations
					For each sensitivity node there is a bar in the graph
						For each state of the sensitivity node get buffered value
						The bar is between min and max such values, labelled
			*/
			
			List<BufferedStatisticKey.STAT> statsToGraph = new ArrayList<>();
			List<Double> originalValues = new ArrayList<>();
			if (sumsMean){
				statsToGraph.add(BufferedStatisticKey.STAT.mean);
				originalValues.add(targetOriginal.getMean());
			}
			if (sumsMedian){
				statsToGraph.add(BufferedStatisticKey.STAT.median);
				originalValues.add(targetOriginal.getMedian());
			}
			if (sumsVariance){
				statsToGraph.add(BufferedStatisticKey.STAT.variance);
				originalValues.add(targetOriginal.getVariance());
			}
			if (sumsStDev){
				statsToGraph.add(BufferedStatisticKey.STAT.standardDeviation);
				originalValues.add(targetOriginal.getStandardDeviation());
			}
			if (sumsLowerPercentile){
				statsToGraph.add(BufferedStatisticKey.STAT.lowerPercentile);
				originalValues.add(targetOriginal.getLowerPercentile());
			}
			if (sumsUpperPercentile){
				statsToGraph.add(BufferedStatisticKey.STAT.upperPercentile);
				originalValues.add(targetOriginal.getUpperPercentile());
			}
			
			
			for(int i = 0; i < statsToGraph.size(); i++){
				
				JSONObject jsonGraph = new JSONObject();
				
				BufferedStatisticKey.STAT statToGraph = statsToGraph.get(i);
				
				jsonGraph.put("summaryStatistic", statToGraph.toString());
				jsonGraph.put("originalValue", originalValues.get(i));
				
				List<JSONObject> jsonBarsList = new ArrayList<>();
				
				for(Node sensNode: sensitivityNodes){
					Map<BufferedStatisticKey, Double> bufferedValues = bufSAStatsLim.get(sensNode);
					List<State> sensStates = sensNode.getStates();

					State stateMin = sensStates.get(0);
					Double valueMin = bufferedValues.get(new BufferedStatisticKey(statToGraph, sensStates.get(0).getLabel()));
					State stateMax = sensStates.get(sensStates.size()-1);
					Double valueMax = bufferedValues.get(new BufferedStatisticKey(statToGraph, sensStates.get(sensStates.size()-1).getLabel()));
					for(State state: sensStates){
						Double value = bufferedValues.get(new BufferedStatisticKey(statToGraph, state.getLabel()));
						if (Double.isNaN(value)){
							continue;
						}
//						System.out.println(statToGraph+"\t"+sensNode.getLogicNode() + "\t" + state.getLogicState() + "\t" + value);//xxxxx
						if(value < valueMin){
							valueMin = value;
							stateMin = state;
						}
						if (value > valueMax){
							valueMax = value;
							stateMax = state;
						}
					}
					
					if (Double.isNaN(valueMax) || Double.isNaN(valueMin)){
						continue;
					}
					
					JSONObject jsonBar = new JSONObject();
					jsonBar.put("diff", valueMax - valueMin);
					jsonBar.put("node", sensNode.getId());
					jsonBar.put("stateMin", stateMin.getLabel());
					jsonBar.put("labelMin", "P(" + sensNode.getName() + " = " + stateMin.getLabel() + ")");
					jsonBar.put("valueMin", valueMin);
					jsonBar.put("stateMax", stateMax.getLabel());
					jsonBar.put("labelMax", "P(" + sensNode.getName() + " = " + stateMax.getLabel() + ")");
					jsonBar.put("valueMax", valueMax);
					jsonBarsList.add(jsonBar);
				}
				
				jsonBarsList.sort((o1, o2) -> {
					// Sort so that biggest bars are on top
					return Double.compare(o2.optDouble("diff"), o1.optDouble("diff"));
				});
				
				JSONArray graphBars = new JSONArray(jsonBarsList);
				jsonGraph.put("graphBars", graphBars);
				
				jsonGraphs.put(jsonGraph);
			}

		}
		else {
			/*
				Graphs are created for each state of the target node
				For each graph:
					The graph represents p(targetNode=targetState)
					There is a line indicating p(targetNode=targetState) without observations
					For each sensitivity node there is a bar in the graph
						For each state of the sensitivity node get buffered value
						The bar is between min and max such values, labelled
			*/
			
			List<State> tarStates = targetNode.getStates();
			for(int tarStateIndex = 0; tarStateIndex < tarStates.size(); tarStateIndex++){
				JSONObject jsonGraph = new JSONObject();
				
				State tarState = tarStates.get(tarStateIndex);
				
				jsonGraph.put("graphTitle", "P(" + targetNode.getName() + " = " + tarState.getLabel() + ")");
				jsonGraph.put("targetState", tarState.getLabel());
				jsonGraph.put("originalValue", bufResultsOriginal.get(targetNode).getResultValue(tarState.getLabel()).getValue());
				
				List<JSONObject> jsonBarsList = new ArrayList<>();
				
				for(Node sensNode: sensitivityNodes){
					Map<BufferedCalculationKey, Double> bufferedValues = bufSACalcs.get(sensNode);
					List<State> sensStates = sensNode.getStates();
					
					State stateMin = sensStates.get(0);
					Double valueMin = bufferedValues.get(new BufferedCalculationKey(targetNode, tarState.getLabel(), sensStates.get(0).getLabel()));
					State stateMax = sensStates.get(sensStates.size()-1);
					Double valueMax = bufferedValues.get(new BufferedCalculationKey(targetNode, tarState.getLabel(), sensStates.get(sensStates.size()-1).getLabel()));
					
					for(State state: sensStates){
						Double value = bufferedValues.get(new BufferedCalculationKey(targetNode, tarState.getLabel(), state.getLabel()));
						if(value < valueMin){
							valueMin = value;
							stateMin = state;
						}
						if (value > valueMax){
							valueMax = value;
							stateMax = state;
						}
					}
					
					//new DecimalFormat("##0.###").format(valueMin));
					
					JSONObject jsonBar = new JSONObject();
					jsonBar.put("diff", valueMax - valueMin);
					jsonBar.put("node", sensNode.getId());
					jsonBar.put("stateMin", stateMin.getLabel());
					jsonBar.put("labelMin", "P(" + sensNode.getName() + " = " + stateMin.getLabel() + ")");
					jsonBar.put("valueMin", valueMin);
					jsonBar.put("stateMax", stateMax.getLabel());
					jsonBar.put("labelMax", "P(" + sensNode.getName() + " = " + stateMax.getLabel() + ")");
					jsonBar.put("valueMax", valueMax);
					
					jsonBarsList.add(jsonBar);
				}
				
				jsonBarsList.sort((o1, o2) -> {
					// Sort so that biggest bars are on top
					return Double.compare(o2.optDouble("diff"), o1.optDouble("diff"));
				});
				
				JSONArray graphBars = new JSONArray(jsonBarsList);
				jsonGraph.put("graphBars", graphBars);
				
				jsonGraphs.put(jsonGraph);
			}
		}
		
		return jsonGraphs;
	}
	
	public JSONObject buildCurves(){
		return null;
	}

	/**
	 * Explores and calculates possible combinations of observed states for target and sensitivity nodes.
	 * 
	 * @throws SensitivityAnalyserException upon failure
	 */
	private void calculateCombinations() throws SensitivityAnalyserException {
		bufResultsOriginal = dataSet.getCalculationResults(targetNode.getNetwork());

		CalculationResult tarCalcOri = bufResultsOriginal.get(targetNode);
		ArrayList<ResultValue> tarResValOri = new ArrayList<>(tarCalcOri.getResultValues());

		List<State> tarStates = targetNode.getStates();
		for (int indexTarResVal = 0; indexTarResVal < targetNode.getLogicNode().getExtendedStates().size(); indexTarResVal++) {
			State tarState = tarStates.get(indexTarResVal);

			String tarObsVal = tarState.getLabel();

			if (targetNode.isNumericInterval()) {
				tarObsVal = "" + tarState.getLogicState().getNumericalValue();
			}

			dataSet.setObservation(targetNode, tarObsVal);
			try {
				model.calculate();
			}
			catch (InconsistentEvidenceException ex) {
				// Inconsistent evidence means we just skip this state
				// For other calculation failures we exit analysis altogether
				continue;
			}
			catch (CalculationException ex) {
				throw new SensitivityAnalyserException("Calculation failure", ex);
			}

			Map<Node, CalculationResult> resultsSubjective = dataSet.getCalculationResults(targetNode.getNetwork());

			ResultValue tvO = tarResValOri.get(indexTarResVal);

			CalculationResult tarCalcSub = resultsSubjective.get(targetNode);
			ArrayList<ResultValue> tarResValSub = new ArrayList<>(tarCalcSub.getResultValues());
			ResultValue tvS = tarResValSub.get(indexTarResVal);

			if (tarResValSub.size() != targetNode.getLogicNode().getExtendedStates().size()) {
				throw new SensitivityAnalyserException("Calculation result size does not match for target node");
			}

			for (Node sensitivityNode : sensitivityNodes) {

				// Record p(T = t|e, X = x), p(T = t | e), p(X = x|e)
				if (!bufSACalcs.containsKey(sensitivityNode)) {
					bufSACalcs.put(sensitivityNode, new LinkedHashMap<>());
				}

				CalculationResult sensCalcOri = bufResultsOriginal.get(sensitivityNode);
				CalculationResult sensCalcSub = resultsSubjective.get(sensitivityNode);

				ArrayList<ResultValue> sensResValOri = new ArrayList<>(sensCalcOri.getResultValues());
				ArrayList<ResultValue> sensResValSub = new ArrayList<>(sensCalcSub.getResultValues());

				if (sensResValOri.size() != sensResValSub.size()) {
					throw new SensitivityAnalyserException("Calculation result size does not match for node " + sensitivityNode.toStringExtra());
				}

				List<State> sensStates = sensitivityNode.getStates();
				for (int indexSensResVal = 0; indexSensResVal < sensResValOri.size(); indexSensResVal++) {
					State sensState = sensStates.get(indexSensResVal);
					ResultValue rvO = sensResValOri.get(indexSensResVal);
					ResultValue rvS = sensResValSub.get(indexSensResVal);

					double value = rvS.getValue();
					// item divided by p(S)
					double reverse = (value * tvO.getValue()) / rvO.getValue();

					bufSACalcs.get(sensitivityNode).put(
							new BufferedCalculationKey(sensitivityNode, sensState.getLabel(), tarState.getLabel()),
							value
					);

					bufSACalcs.get(sensitivityNode).put(
							new BufferedCalculationKey(targetNode, tarState.getLabel(), sensState.getLabel()),
							reverse
					);
				}
			}
		}
		dataSet.clearObservation(targetNode);
	}

	/**
	 * Calculates summary statistics and limited summary statistics.
	 * 
	 * @throws SensitivityAnalyserException upon failure
	 */
	private void calculateStats() throws SensitivityAnalyserException {
		List<State> tarStates = targetNode.getStates();
		
		for (Node sensitivityNode : sensitivityNodes) {

			if (!Arrays.asList(Node.Type.ContinuousInterval, Node.Type.IntegerInterval, Node.Type.Ranked).contains(sensitivityNode.getType())) {
				// Skip inherently labelled nodes
//				System.out.println("skip non cont");
//				continue;
			}

			uk.co.agena.minerva.util.model.DataSet tempA1ResultsOriginal = (uk.co.agena.minerva.util.model.DataSet) bufResultsOriginal.get(sensitivityNode).getLogicCalculationResult().getDataset().clone();

			if (sensitivityNode.isNumericInterval()) {
				try {
					double actUpperP = MathsHelper.percentile(sensUpperPercentileValue, tempA1ResultsOriginal);
					double actLowerP = MathsHelper.percentile(sensLowerPercentileValue, tempA1ResultsOriginal);
					for (int i = 0; i < tempA1ResultsOriginal.size(); i++) {
						IntervalDataPoint dp = (IntervalDataPoint) tempA1ResultsOriginal.getDataPointAtOrderPosition(i);
						if (dp.getIntervalUpperBound() < actLowerP || dp.getIntervalLowerBound() > actUpperP) {
							dp.setValue(Double.NaN);
						}
					}
				}
				catch (Exception ex) {
					throw new SensitivityAnalyserException("Failed to apply continuous node sensitivity percentiles", ex);
				}
			}

			if (!bufSAStats.containsKey(sensitivityNode)) {
				bufSAStats.put(sensitivityNode, new LinkedHashMap<>());
			}

			if (!bufSAStatsLim.containsKey(sensitivityNode)) {
				bufSAStatsLim.put(sensitivityNode, new LinkedHashMap<>());
			}

			List<State> sensStates = sensitivityNode.getStates();
			for (int indexSensState = 0; indexSensState < sensitivityNode.getLogicNode().getExtendedStates().size(); indexSensState++) {
				State sensState = sensStates.get(indexSensState);

				double mean = 0.0, median = 0.0, variance = 0.0;
				double meanLim = 0.0, medianLim = 0.0, varianceLim = 0.0;
				double standardDeviation = 0.0, upperPercentile = 0.0, lowerPercentile = 0.0;
				double standardDeviationLim = 0.0, upperPercentileLim = 0.0, lowerPercentileLim = 0.0;

				double[] xVals = new double[tarStates.size()];
				double[] pXs = new double[tarStates.size()];
				double[] pXsWithZero = new double[tarStates.size()];
				Range[] xIntervals = new Range[tarStates.size()];

				boolean limAllNAN = Double.isNaN(bufResultsOriginal.get(sensitivityNode).getResultValues().get(indexSensState).getValue());
				
				uk.co.agena.minerva.util.model.DataSet targetA1DataSet = new uk.co.agena.minerva.util.model.DataSet();
				uk.co.agena.minerva.util.model.DataSet targetA1DataSetLim = new uk.co.agena.minerva.util.model.DataSet();

				for (int indexTarState = 0; indexTarState < tarStates.size(); indexTarState++) {
					State tarState = tarStates.get(indexTarState);
					Range r = tarState.getLogicState().getRange();
					try {
						r = MathsHelper.scaleInfinities(r);
					}
					catch (Exception ex) {
						throw new SensitivityAnalyserException("Failed to scale infinities for state range " + r, ex);
					}
					xIntervals[indexTarState] = r;

					if (bufResultsOriginal.get(targetNode).getResultValues().get(indexTarState).getValue() == 0) {
						// This branch was impossible with target node, skip it
						continue;
					}
//					System.out.println(bufSACalcs);
//					System.out.println("get node: " + sensitivityNode);
//					System.out.println(bufSACalcs.get(sensitivityNode));
//					System.out.println("get key: ");
//					System.out.println(new BufferedCalculationKey(targetNode, tarState.getLabel(), sensState.getLabel()));
					double dbl = bufSACalcs.get(sensitivityNode).get(new BufferedCalculationKey(targetNode, tarState.getLabel(), sensState.getLabel()));
					double dblWithZero = Double.NaN;

					try {
						if (!Double.isNaN(tempA1ResultsOriginal.getDataPointAtOrderPosition(indexSensState).getValue())) {
							dblWithZero = dbl;
						}
					}
					catch (Exception ex) {
						throw new SensitivityAnalyserException(ex);
					}

					xVals[indexTarState] = tarState.getLogicState().getNumericalValue();
					pXs[indexTarState] = dbl;
					pXsWithZero[indexTarState] = dblWithZero;

					// want to create a dataSet to pass to new variance calcualtion routine                    
					IntervalDataPoint idp = new IntervalDataPoint();
					idp.setValue(dbl);
					idp.setIntervalLowerBound(r.getLowerBound());
					idp.setIntervalUpperBound(r.getUpperBound());
					targetA1DataSet.addDataPoint(idp);
					
					IntervalDataPoint idpWithZero = new IntervalDataPoint();
					idpWithZero.setValue(dblWithZero);
					idpWithZero.setIntervalLowerBound(r.getLowerBound());
					idpWithZero.setIntervalUpperBound(r.getUpperBound());
					targetA1DataSetLim.addDataPoint(idpWithZero);
					
//					System.out.println("~"+sensitivityNode.getLogicNode()+"\t"+sensState.getLogicState()+"\t"+tarState.getLogicState()+"\t"+dbl+"\t"+idp);

					// get all p (T | Sn)
				}

				try {
					mean = MathsHelper.mean(pXs, xVals);
					meanLim = limAllNAN ? Double.NaN : MathsHelper.mean(pXsWithZero, xVals);
					variance = MathsHelper.variance(targetA1DataSet);
					varianceLim = limAllNAN ? Double.NaN : MathsHelper.variance(targetA1DataSetLim);
					standardDeviation = Math.sqrt(variance);
					standardDeviationLim = limAllNAN ? Double.NaN : Math.sqrt(varianceLim);
//					System.out.println("~"+sensitivityNode.getLogicNode()+"\t"+sensState.getLogicState()+"\t"+mean+"\t"+variance+"\t"+standardDeviation);
//					System.out.println(targetA1DataSet);
				}
				catch (Exception ex) {
					throw new SensitivityAnalyserException("Failed to calculate SA summary statistics", ex);
				}

				if (Double.isNaN(mean) && Double.isNaN(variance)) {
					median = Double.NaN;
					upperPercentile = Double.NaN;
					lowerPercentile = Double.NaN;
					medianLim = Double.NaN;
					upperPercentileLim = Double.NaN;
					lowerPercentileLim = Double.NaN;
				}
				else {
					try {
						median = MathsHelper.percentile(50, pXs, xIntervals);
						upperPercentile = MathsHelper.percentile(sumsUpperPercentileValue, pXs, xIntervals);
						lowerPercentile = MathsHelper.percentile(sumsLowerPercentileValue, pXs, xIntervals);
						medianLim = limAllNAN ? Double.NaN : MathsHelper.percentile(50, pXsWithZero, xIntervals);
						upperPercentileLim = limAllNAN ? Double.NaN : MathsHelper.percentile(sumsUpperPercentileValue, pXsWithZero, xIntervals);
						lowerPercentileLim = limAllNAN ? Double.NaN : MathsHelper.percentile(sumsLowerPercentileValue, pXsWithZero, xIntervals);
					}
					catch (Exception ex) {
						throw new SensitivityAnalyserException("Failed to calculate Sensitivity summary statistics", ex);
					}
				}

				bufSAStats.get(sensitivityNode).put(new BufferedStatisticKey(BufferedStatisticKey.STAT.mean, sensState.getLabel()), mean);
				bufSAStats.get(sensitivityNode).put(new BufferedStatisticKey(BufferedStatisticKey.STAT.median, sensState.getLabel()), median);
				bufSAStats.get(sensitivityNode).put(new BufferedStatisticKey(BufferedStatisticKey.STAT.variance, sensState.getLabel()), variance);
				bufSAStats.get(sensitivityNode).put(new BufferedStatisticKey(BufferedStatisticKey.STAT.standardDeviation, sensState.getLabel()), standardDeviation);
				bufSAStats.get(sensitivityNode).put(new BufferedStatisticKey(BufferedStatisticKey.STAT.upperPercentile, sensState.getLabel()), upperPercentile);
				bufSAStats.get(sensitivityNode).put(new BufferedStatisticKey(BufferedStatisticKey.STAT.lowerPercentile, sensState.getLabel()), lowerPercentile);

				bufSAStatsLim.get(sensitivityNode).put(new BufferedStatisticKey(BufferedStatisticKey.STAT.mean, sensState.getLabel()), meanLim);
				bufSAStatsLim.get(sensitivityNode).put(new BufferedStatisticKey(BufferedStatisticKey.STAT.median, sensState.getLabel()), medianLim);
				bufSAStatsLim.get(sensitivityNode).put(new BufferedStatisticKey(BufferedStatisticKey.STAT.variance, sensState.getLabel()), varianceLim);
				bufSAStatsLim.get(sensitivityNode).put(new BufferedStatisticKey(BufferedStatisticKey.STAT.standardDeviation, sensState.getLabel()), standardDeviationLim);
				bufSAStatsLim.get(sensitivityNode).put(new BufferedStatisticKey(BufferedStatisticKey.STAT.upperPercentile, sensState.getLabel()), upperPercentileLim);
				bufSAStatsLim.get(sensitivityNode).put(new BufferedStatisticKey(BufferedStatisticKey.STAT.lowerPercentile, sensState.getLabel()), lowerPercentileLim);
//				System.out.println("~~~");
//				System.out.println(bufSAStats);
//				System.out.println(bufferedStatsLim);
//				System.out.println("~=~");
			}
		}
	}

	private class BufferedCalculationKey {

		final Node node;
		final String nodeState, calcState;

		public BufferedCalculationKey(Node node, String nodeState, String calcState) {
			this.node = node;
			this.nodeState = nodeState;
			this.calcState = calcState;
		}

		public Node getNode() {
			return node;
		}

		public String getNodeState() {
			return nodeState;
		}

		public String getCalcState() {
			return calcState;
		}

		@Override
		public int hashCode() {
			int hash = 3;
			hash = 67 * hash + Objects.hashCode(this.node);
			hash = 67 * hash + Objects.hashCode(this.nodeState);
			hash = 67 * hash + Objects.hashCode(this.calcState);
			return hash;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (getClass() != obj.getClass()) {
				return false;
			}

			final BufferedCalculationKey other = (BufferedCalculationKey) obj;

			if (!Objects.equals(this.nodeState, other.nodeState)) {
				return false;
			}
			if (!Objects.equals(this.calcState, other.calcState)) {
				return false;
			}
			if (!Objects.equals(this.node, other.node)) {
				return false;
			}
			return true;
		}
		
		public JSONObject toJson(){
			JSONObject json = new JSONObject();
			json.put("node", node.toStringExtra());
			json.put("nodeState", nodeState);
			json.put("calcState", calcState);
			return json;
		}

		@Override
		public String toString() {
			return toJson().toString();
		}
	}

	private static class BufferedStatisticKey {

		enum STAT {
			mean,
			median,
			variance,
			standardDeviation,
			upperPercentile,
			lowerPercentile
		}

		private final STAT stat;
		private final String calcState;

		public BufferedStatisticKey(STAT stat, String calcState) {
			this.stat = stat;
			this.calcState = calcState;
		}

		public STAT getStat() {
			return stat;
		}

		public String getCalcState() {
			return calcState;
		}

		@Override
		public int hashCode() {
			int hash = 7;
			hash = 29 * hash + Objects.hashCode(this.stat);
			hash = 29 * hash + Objects.hashCode(this.calcState);
			return hash;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (getClass() != obj.getClass()) {
				return false;
			}
			final BufferedStatisticKey other = (BufferedStatisticKey) obj;
			if (!Objects.equals(this.calcState, other.calcState)) {
				return false;
			}
			if (this.stat != other.stat) {
				return false;
			}
			return true;
		}
		
		public JSONObject toJson(){
			JSONObject json = new JSONObject();
			json.put("summaryStatistic", stat);
			json.put("calcState", calcState);
			return json;
		}

		@Override
		public String toString() {
			return toJson().toString();
		}

	}
	
	public JSONObject getConfig(){
		JSONObject jsonConfig = new JSONObject();
		
		jsonConfig.put("targetNode", targetNode.getId());
		jsonConfig.put("network", targetNode.getNetwork().getId());
		jsonConfig.put("dataSet", dataSet.getId());
		
		JSONArray sensitivityNodes = new JSONArray();
		jsonConfig.put("sensitivityNodes", sensitivityNodes);
		this.sensitivityNodes.forEach(node -> {
			sensitivityNodes.put(node.getId());
		});
		
		JSONObject jsonReportSettings = new JSONObject();
		jsonConfig.put("reportSettings", jsonReportSettings);
		if (sumsMean){
			jsonReportSettings.put("sumsMean", true);
		}
		if (sumsMedian){
			jsonReportSettings.put("sumsMedian", true);
		}
		if (sumsVariance){
			jsonReportSettings.put("sumsVariance", true);
		}
		if (sumsStDev){
			jsonReportSettings.put("sumsStDev", true);
		}
		if (sumsLowerPercentile){
			jsonReportSettings.put("sumsLowerPercentile", true);
		}
		if (sumsUpperPercentile){
			jsonReportSettings.put("sumsUpperPercentile", true);
		}
		jsonReportSettings.put("sumsLowerPercentileValue", sumsLowerPercentileValue);
		jsonReportSettings.put("sumsUpperPercentileValue", sumsUpperPercentileValue);
		jsonReportSettings.put("sensLowerPercentileValue", sensLowerPercentileValue);
		jsonReportSettings.put("sensUpperPercentileValue", sensUpperPercentileValue);
		
		return jsonConfig;
	}

}