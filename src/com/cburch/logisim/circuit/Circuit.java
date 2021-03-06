/*******************************************************************************
 * This file is part of logisim-evolution.
 *
 *   logisim-evolution is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   logisim-evolution is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with logisim-evolution.  If not, see <http://www.gnu.org/licenses/>.
 *
 *   Original code by Carl Burch (http://www.cburch.com), 2011.
 *   Subsequent modifications by :
 *     + Haute École Spécialisée Bernoise
 *       http://www.bfh.ch
 *     + Haute École du paysage, d'ingénierie et d'architecture de Genève
 *       http://hepia.hesge.ch/
 *     + Haute École d'Ingénierie et de Gestion du Canton de Vaud
 *       http://www.heig-vd.ch/
 *   The project is currently maintained by :
 *     + REDS Institute - HEIG-VD
 *       Yverdon-les-Bains, Switzerland
 *       http://reds.heig-vd.ch
 *******************************************************************************/

package com.cburch.logisim.circuit;

import java.awt.Graphics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bfh.logisim.designrulecheck.CorrectLabel;
import com.bfh.logisim.designrulecheck.Netlist;
import com.bfh.logisim.fpgagui.FPGAReport;
import com.cburch.logisim.circuit.appear.CircuitAppearance;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.comp.ComponentEvent;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.comp.ComponentListener;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.FailException;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.TestException;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Clock;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.util.CollectionUtil;
import com.cburch.logisim.util.EventSourceWeakSupport;

public class Circuit {
	private class EndChangedTransaction extends CircuitTransaction {
		private Component comp;
		private Map<Location, EndData> toRemove;
		private Map<Location, EndData> toAdd;

		EndChangedTransaction(Component comp, Map<Location, EndData> toRemove,
				Map<Location, EndData> toAdd) {
			this.comp = comp;
			this.toRemove = toRemove;
			this.toAdd = toAdd;
		}

		@Override
		protected Map<Circuit, Integer> getAccessedCircuits() {
			return Collections.singletonMap(Circuit.this, READ_WRITE);
		}

		@Override
		protected void run(CircuitMutator mutator) {
			for (Location loc : toRemove.keySet()) {
				EndData removed = toRemove.get(loc);
				EndData replaced = toAdd.remove(loc);
				if (replaced == null) {
					wires.remove(comp, removed);
				} else if (!replaced.equals(removed)) {
					wires.replace(comp, removed, replaced);
				}
			}
			for (EndData end : toAdd.values()) {
				wires.add(comp, end);
			}
			((CircuitMutatorImpl) mutator).markModified(Circuit.this);
		}
	}

	private class MyComponentListener implements ComponentListener {
		public void componentInvalidated(ComponentEvent e) {
			fireEvent(CircuitEvent.ACTION_INVALIDATE, e.getSource());
		}

		public void endChanged(ComponentEvent e) {
			locker.checkForWritePermission("ends changed");
			Annotated = false;
			MyNetList.clear();
			Component comp = e.getSource();
			HashMap<Location, EndData> toRemove = toMap(e.getOldData());
			HashMap<Location, EndData> toAdd = toMap(e.getData());
			EndChangedTransaction xn = new EndChangedTransaction(comp,
					toRemove, toAdd);
			locker.execute(xn);
			fireEvent(CircuitEvent.ACTION_INVALIDATE, comp);
		}

		private HashMap<Location, EndData> toMap(Object val) {
			HashMap<Location, EndData> map = new HashMap<Location, EndData>();
			if (val instanceof List) {
				@SuppressWarnings("unchecked")
				List<EndData> valList = (List<EndData>) val;
				for (EndData end : valList) {
					if (end != null) {
						map.put(end.getLocation(), end);
					}
				}
			} else if (val instanceof EndData) {
				EndData end = (EndData) val;
				map.put(end.getLocation(), end);
			}
			return map;
		}
	}

	//
	// helper methods for other classes in package
	//
	public static boolean isInput(Component comp) {
		return comp.getEnd(0).getType() != EndData.INPUT_ONLY;
	}

	private MyComponentListener myComponentListener = new MyComponentListener();
	private CircuitAppearance appearance;
	private AttributeSet staticAttrs;
	private SubcircuitFactory subcircuitFactory;
	private EventSourceWeakSupport<CircuitListener> listeners = new EventSourceWeakSupport<CircuitListener>();
	private HashSet<Component> comps = new HashSet<Component>(); // doesn't
																	// include
																	// wires
	CircuitWires wires = new CircuitWires();
	// wires is package-protected for CircuitState and Analyze only.
	private ArrayList<Component> clocks = new ArrayList<Component>();
	private CircuitLocker locker;

	final static Logger logger = LoggerFactory.getLogger(Circuit.class);

	private WeakHashMap<Component, Circuit> circuitsUsingThis;
	private Netlist MyNetList;
	private boolean Annotated;

	private LogisimFile logiFile;

	public Circuit(String name, LogisimFile file) {
		appearance = new CircuitAppearance(this);
		staticAttrs = CircuitAttributes.createBaseAttrs(this, name);
		subcircuitFactory = new SubcircuitFactory(this);
		locker = new CircuitLocker();
		circuitsUsingThis = new WeakHashMap<Component, Circuit>();
		MyNetList = new Netlist(this);
		Annotated = false;
		logiFile = file;
	}

	//
	// Listener methods
	//
	public void addCircuitListener(CircuitListener what) {
		listeners.add(what);
	}

	public void Annotate(boolean ClearExistingLabels, FPGAReport reporter) {
		ArrayList<Integer> CompCount = new ArrayList<Integer>();
		ArrayList<String> CompName = new ArrayList<String>();
		ArrayList<Set<String>> AnnotationNames = new ArrayList<Set<String>>();
		/* If I am already completely annotated, return */
		if (Annotated) {
			reporter.AddInfo("Nothing to do !");
			return;
		}
		/* in case of label cleaning, we clear first all old labels */
		if (ClearExistingLabels) {
			for (Component comp : this.getNonWires()) {
				if (!comp.getFactory().RequiresNonZeroLabel())
					continue;
				reporter.AddInfo("Cleared " + this.getName() + "/"
						+ comp.getAttributeSet().getValue(StdAttr.LABEL));
				comp.getAttributeSet().setValue(StdAttr.LABEL, "");
			}
		}
		for (Component comp : this.getNonWires()) {
			/* Ignore all components that do not require a label */
			if (!comp.getFactory().RequiresNonZeroLabel())
				continue;
			/*
			 * Add the component to the set of components if it is not already
			 * added
			 */
			String ComponentName;
			/* Pins are treated specially */
			if (comp.getFactory() instanceof Pin) {
				if (comp.getEnd(0).isOutput()) {
					if (comp.getEnd(0).getWidth().getWidth() > 1) {
						ComponentName = "Input_bus";
					} else {
						ComponentName = "Input";
					}
				} else {
					if (comp.getEnd(0).getWidth().getWidth() > 1) {
						ComponentName = "Output_bus";
					} else {
						ComponentName = "Output";
					}
				}
			} else {
				ComponentName = comp.getFactory().getHDLName(
						comp.getAttributeSet());
			}
			if (!CompName.contains(ComponentName)) {
				CompCount.add(1);
				CompName.add(ComponentName);
				AnnotationNames.add(new HashSet<String>());
			}
			/* If the label is non-zero add them to the list of AnnotationNames */
			String Label = CorrectLabel.getCorrectLabel(comp.getAttributeSet()
					.getValue(StdAttr.LABEL).toString());
			if (!Label.isEmpty()) {
				int compindex = CompName.indexOf(ComponentName);
				AnnotationNames.get(compindex).add(Label);
			}
			/* if the current component is a sub-circuit, recurse into it */
			if (comp.getFactory() instanceof SubcircuitFactory) {
				SubcircuitFactory sub = (SubcircuitFactory) comp.getFactory();
				sub.getSubcircuit().Annotate(ClearExistingLabels, reporter);
			}
		}
		/* Here the annotation process takes place */
		for (Component comp : this.getNonWires()) {
			/* Ignore all components that do not require a label */
			if (!comp.getFactory().RequiresNonZeroLabel())
				continue;
			/* Ignore all components that already have a label */
			String Label = CorrectLabel.getCorrectLabel(comp.getAttributeSet()
					.getValue(StdAttr.LABEL).toString());
			if (!Label.isEmpty())
				continue;
			String CorrectComponentName;
			/* Pins are treated specially */
			if (comp.getFactory() instanceof Pin) {
				if (comp.getEnd(0).isOutput()) {
					if (comp.getEnd(0).getWidth().getWidth() > 1) {
						CorrectComponentName = "Input_bus";
					} else {
						CorrectComponentName = "Input";
					}
				} else {
					if (comp.getEnd(0).getWidth().getWidth() > 1) {
						CorrectComponentName = "Output_bus";
					} else {
						CorrectComponentName = "Output";
					}
				}
				if (!CompName.contains(CorrectComponentName)) {
					CompName.add(CorrectComponentName);
					CompCount.add(1);
					AnnotationNames.add(new HashSet<String>());
				}
			} else {
				CorrectComponentName = comp.getFactory().getHDLName(
						comp.getAttributeSet());
				if (!CompName.contains(CorrectComponentName)) {
					/* This should never happen */
					continue;
				}
			}
			int index = CompName.indexOf(CorrectComponentName);
			/* first we find a non-existing label */
			Integer id = CompCount.get(index);
			while (AnnotationNames.get(index).contains(
					CorrectComponentName + "_" + id.toString())) {
				id++;
			}
			CompCount.set(index, id + 1);
			String NewLabel = CorrectComponentName + "_" + id.toString();
			/*
			 * TODO: Dirty hack; I do not know how to change the label in such a
			 * way that the whole project is correctly notified so I just change
			 * the label here and do some dirty updates in
			 * "FPGACommanderGUI.java"
			 */
			reporter.AddInfo("Labeled " + this.getName() + "/" + NewLabel);
			comp.getAttributeSet().setValue(StdAttr.LABEL, NewLabel);
			AnnotationNames.get(index).add(NewLabel);
		}
		Annotated = true;
	}

	//
	// Annotation module for all components that require a non-zero-length label
	public void ClearAnnotationLevel() {
		Annotated = false;
		MyNetList.clear();
		for (Component comp : this.getNonWires()) {
			if (comp.getFactory() instanceof SubcircuitFactory) {
				SubcircuitFactory sub = (SubcircuitFactory) comp.getFactory();
				sub.getSubcircuit().ClearAnnotationLevel();
			}

		}
	}

	public boolean contains(Component c) {
		return comps.contains(c) || wires.getWires().contains(c);
	}

	/**
	 * Code taken from Cornell's version of Logisim:
	 * http://www.cs.cornell.edu/courses/cs3410/2015sp/
	 */
	public void doTestVector(Project project, Instance pin[], Value[] val)
			throws TestException {
		CircuitState state = project.getCircuitState();
		state.reset();

		for (int i = 0; i < pin.length; ++i) {
			if (Pin.FACTORY.isInputPin(pin[i])) {
				InstanceState pinState = state.getInstanceState(pin[i]);
				Pin.FACTORY.setValue(pinState, val[i]);
			}
		}

		Propagator prop = state.getPropagator();

		try {
			prop.propagate();
		} catch (Throwable thr) {
			thr.printStackTrace();
		}

		if (prop.isOscillating())
			throw new TestException("oscilation detected");

		FailException err = null;

		for (int i = 0; i < pin.length; i++) {
			InstanceState pinState = state.getInstanceState(pin[i]);
			if (Pin.FACTORY.isInputPin(pin[i]))
				continue;

			Value v = Pin.FACTORY.getValue(pinState);
			if (!val[i].compatible(v)) {
				if (err == null)
					err = new FailException(i,
							pinState.getAttributeValue(StdAttr.LABEL), val[i],
							v);
				else
					err.add(new FailException(i, pinState
							.getAttributeValue(StdAttr.LABEL), val[i], v));
			}
		}

		if (err != null) {
			throw err;
		}
	}

	//
	// Graphics methods
	//
	public void draw(ComponentDrawContext context, Collection<Component> hidden) {
		Graphics g = context.getGraphics();
		Graphics g_copy = g.create();
		context.setGraphics(g_copy);
		wires.draw(context, hidden);

		if (hidden == null || hidden.size() == 0) {
			for (Component c : comps) {
				Graphics g_new = g.create();
				context.setGraphics(g_new);
				g_copy.dispose();
				g_copy = g_new;

				c.draw(context);
			}
		} else {
			for (Component c : comps) {
				if (!hidden.contains(c)) {
					Graphics g_new = g.create();
					context.setGraphics(g_new);
					g_copy.dispose();
					g_copy = g_new;

					try {
						c.draw(context);
					} catch (RuntimeException e) {
						// this is a JAR developer error - display it and move
						// on
						e.printStackTrace();
					}
				}
			}
		}
		context.setGraphics(g);
		g_copy.dispose();
	}

	private void fireEvent(CircuitEvent event) {
		for (CircuitListener l : listeners) {
			l.circuitChanged(event);
		}
	}

	void fireEvent(int action, Object data) {
		fireEvent(new CircuitEvent(action, this, data));
	}

	public Collection<Component> getAllContaining(Location pt) {
		HashSet<Component> ret = new HashSet<Component>();
		for (Component comp : getComponents()) {
			if (comp.contains(pt))
				ret.add(comp);
		}
		return ret;
	}

	public Collection<Component> getAllContaining(Location pt, Graphics g) {
		HashSet<Component> ret = new HashSet<Component>();
		for (Component comp : getComponents()) {
			if (comp.contains(pt, g))
				ret.add(comp);
		}
		return ret;
	}

	public Collection<Component> getAllWithin(Bounds bds) {
		HashSet<Component> ret = new HashSet<Component>();
		for (Component comp : getComponents()) {
			if (bds.contains(comp.getBounds()))
				ret.add(comp);
		}
		return ret;
	}

	public Collection<Component> getAllWithin(Bounds bds, Graphics g) {
		HashSet<Component> ret = new HashSet<Component>();
		for (Component comp : getComponents()) {
			if (bds.contains(comp.getBounds(g)))
				ret.add(comp);
		}
		return ret;
	}

	public CircuitAppearance getAppearance() {
		return appearance;
	}

	public Bounds getBounds() {
		Bounds wireBounds = wires.getWireBounds();
		Iterator<Component> it = comps.iterator();
		if (!it.hasNext())
			return wireBounds;
		Component first = it.next();
		Bounds firstBounds = first.getBounds();
		int xMin = firstBounds.getX();
		int yMin = firstBounds.getY();
		int xMax = xMin + firstBounds.getWidth();
		int yMax = yMin + firstBounds.getHeight();
		while (it.hasNext()) {
			Component c = it.next();
			Bounds bds = c.getBounds();
			int x0 = bds.getX();
			int x1 = x0 + bds.getWidth();
			int y0 = bds.getY();
			int y1 = y0 + bds.getHeight();
			if (x0 < xMin)
				xMin = x0;
			if (x1 > xMax)
				xMax = x1;
			if (y0 < yMin)
				yMin = y0;
			if (y1 > yMax)
				yMax = y1;
		}
		Bounds compBounds = Bounds.create(xMin, yMin, xMax - xMin, yMax - yMin);
		if (wireBounds.getWidth() == 0 || wireBounds.getHeight() == 0) {
			return compBounds;
		} else {
			return compBounds.add(wireBounds);
		}
	}

	public Bounds getBounds(Graphics g) {
		Bounds ret = wires.getWireBounds();
		int xMin = ret.getX();
		int yMin = ret.getY();
		int xMax = xMin + ret.getWidth();
		int yMax = yMin + ret.getHeight();
		if (ret == Bounds.EMPTY_BOUNDS) {
			xMin = Integer.MAX_VALUE;
			yMin = Integer.MAX_VALUE;
			xMax = Integer.MIN_VALUE;
			yMax = Integer.MIN_VALUE;
		}
		for (Component c : comps) {
			Bounds bds = c.getBounds(g);
			if (bds != null && bds != Bounds.EMPTY_BOUNDS) {
				int x0 = bds.getX();
				int x1 = x0 + bds.getWidth();
				int y0 = bds.getY();
				int y1 = y0 + bds.getHeight();
				if (x0 < xMin)
					xMin = x0;
				if (x1 > xMax)
					xMax = x1;
				if (y0 < yMin)
					yMin = y0;
				if (y1 > yMax)
					yMax = y1;
			}
		}
		if (xMin > xMax || yMin > yMax)
			return Bounds.EMPTY_BOUNDS;
		return Bounds.create(xMin, yMin, xMax - xMin, yMax - yMin);
	}

	public Collection<Circuit> getCircuitsUsingThis() {
		return circuitsUsingThis.values();
	}

	public ArrayList<Component> getClocks() {
		return clocks;
	}

	private Set<Component> getComponents() {
		return CollectionUtil.createUnmodifiableSetUnion(comps,
				wires.getWires());
	}

	public Collection<? extends Component> getComponents(Location loc) {
		return wires.points.getComponents(loc);
	}

	public Component getExclusive(Location loc) {
		return wires.points.getExclusive(loc);
	}

	CircuitLocker getLocker() {
		return locker;
	}

	//
	// access methods
	//
	public String getName() {
		return staticAttrs.getValue(CircuitAttributes.NAME_ATTR);
	}

	public Netlist getNetList() {
		return MyNetList;
	}

	public Set<Component> getNonWires() {
		return comps;
	}

	public Collection<? extends Component> getNonWires(Location loc) {
		return wires.points.getNonWires(loc);
	}

	public String getProjName() {
		return logiFile == null ? "" : logiFile.getName();
	}

	public Collection<? extends Component> getSplitCauses(Location loc) {
		return wires.points.getSplitCauses(loc);
	}

	public Set<Location> getSplitLocations() {
		return wires.points.getSplitLocations();
	}

	public AttributeSet getStaticAttributes() {
		return staticAttrs;
	}

	public SubcircuitFactory getSubcircuitFactory() {
		return subcircuitFactory;
	}

	public BitWidth getWidth(Location p) {
		return wires.getWidth(p);
	}

	public Location getWidthDeterminant(Location p) {
		return wires.getWidthDeterminant(p);
	}

	public Set<WidthIncompatibilityData> getWidthIncompatibilityData() {
		return wires.getWidthIncompatibilityData();
	}

	public Set<Wire> getWires() {
		return wires.getWires();
	}

	public Collection<Wire> getWires(Location loc) {
		return wires.points.getWires(loc);
	}

	public WireSet getWireSet(Wire start) {
		return wires.getWireSet(start);
	}

	public boolean hasConflict(Component comp) {
		return wires.points.hasConflict(comp);
	}

	public boolean isConnected(Location loc, Component ignore) {
		for (Component o : wires.points.getComponents(loc)) {
			if (o != ignore)
				return true;
		}
		return false;
	}

	void mutatorAdd(Component c) {
		// logger.debug("mutatorAdd: {}", c);

		locker.checkForWritePermission("add");

		Annotated = false;
		MyNetList.clear();
		if (c instanceof Wire) {
			Wire w = (Wire) c;
			if (w.getEnd0().equals(w.getEnd1()))
				return;
			boolean added = wires.add(w);
			if (!added)
				return;
		} else {
			// add it into the circuit
			boolean added = comps.add(c);
			if (!added)
				return;

			wires.add(c);
			ComponentFactory factory = c.getFactory();
			if (factory instanceof Clock) {
				clocks.add(c);
			} else if (factory instanceof SubcircuitFactory) {
				SubcircuitFactory subcirc = (SubcircuitFactory) factory;
				subcirc.getSubcircuit().circuitsUsingThis.put(c, this);
			}
			c.addComponentListener(myComponentListener);
			// c.addComponentListener(this.);
		}
		fireEvent(CircuitEvent.ACTION_ADD, c);
	}

	public void mutatorClear() {
		locker.checkForWritePermission("clear");

		Set<Component> oldComps = comps;
		comps = new HashSet<Component>();
		wires = new CircuitWires();
		clocks.clear();
		MyNetList.clear();
		Annotated = false;
		for (Component comp : oldComps) {
			if (comp.getFactory() instanceof SubcircuitFactory) {
				SubcircuitFactory sub = (SubcircuitFactory) comp.getFactory();
				sub.getSubcircuit().circuitsUsingThis.remove(comp);
			}
		}
		fireEvent(CircuitEvent.ACTION_CLEAR, oldComps);
	}

	void mutatorRemove(Component c) {
		//logger.debug("mutatorRemove: {}", c);

		locker.checkForWritePermission("remove");

		Annotated = false;
		MyNetList.clear();
		if (c instanceof Wire) {
			wires.remove(c);
		} else {
			wires.remove(c);
			comps.remove(c);
			ComponentFactory factory = c.getFactory();
			if (factory instanceof Clock) {
				clocks.remove(c);
			} else if (factory instanceof SubcircuitFactory) {
				SubcircuitFactory subcirc = (SubcircuitFactory) factory;
				subcirc.getSubcircuit().circuitsUsingThis.remove(c);
			}
			c.removeComponentListener(myComponentListener);
		}
		fireEvent(CircuitEvent.ACTION_REMOVE, c);
	}

	public void removeCircuitListener(CircuitListener what) {
		listeners.remove(what);
	}

	//
	// action methods
	//
	public void setName(String name) {
		staticAttrs.setValue(CircuitAttributes.NAME_ATTR, name);
	}

	@Override
	public String toString() {
		return staticAttrs.getValue(CircuitAttributes.NAME_ATTR);
	}
}
