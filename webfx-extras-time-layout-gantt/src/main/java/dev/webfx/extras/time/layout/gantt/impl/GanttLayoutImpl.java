package dev.webfx.extras.time.layout.gantt.impl;

import dev.webfx.extras.geometry.Bounds;
import dev.webfx.extras.geometry.MutableBounds;
import dev.webfx.extras.time.layout.TimeProjector;
import dev.webfx.extras.time.layout.gantt.GanttLayout;
import dev.webfx.extras.time.layout.gantt.HeaderPosition;
import dev.webfx.extras.time.layout.impl.ChildBounds;
import dev.webfx.extras.time.layout.impl.TimeLayoutBase;
import dev.webfx.extras.time.layout.impl.TimeLayoutUtil;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * @author Bruno Salmon
 */
public class GanttLayoutImpl<C, T extends Temporal> extends TimeLayoutBase<C, T> implements GanttLayout<C, T> {

    // Input fields
    private Function<C, ?> childParentReader;
    private Function<C, ?> childGrandparentReader;
    private Function<Object, ?> parentGrandparentReader;
    private Function<C, Double> childTetrisMinWidthReader;
    private HeaderPosition grandparentHeaderPosition = HeaderPosition.TOP;
    private double grandparentHeaderWidth = 150; // Used only for LEFT & RIGHT position
    private double grandparentHeaderHeight = 80; // arbitrary non-null default value (so grandparent rows will appear even if
    // the application code forgot to call setGrandparentHeight())
    private HeaderPosition parentHeaderPosition = HeaderPosition.BOTTOM;
    private double parentHeaderWidth;
    private double parentHeaderHeight = 20;
    private boolean tetrisPacking;
    private final BooleanProperty parentsProvidedProperty = new SimpleBooleanProperty() {
        @Override
        protected void invalidated() {
            if (get())
                invalidateProvidedTree();
            else
                invalidateChildrenTree();
        }
    };
    private final ObservableList<Object> parents = FXCollections.observableArrayList();

    // Output fields
    private final List<ParentRow<C>> parentRows = new ArrayList<>();
    private final List<GrandparentRow> grandparentRows = new ArrayList<>();
    private Map<Object, ParentRow<C>> parentToParentRowMap = new HashMap<>(); // "final" between 2 recycling
    private Map<Object, GrandparentRow> grandparentToGrandparentRowMap = new HashMap<>(); // "final" between 2 recycling

    // Internal version management
    int childrenTreeVersion, builtChildrenTreeVersion; // impact the whole tree (grandparents + parents + children)
    int providedTreeVersion, builtProvidedTreeVersion; // impact grandparents + parents only (used only if parents are provided)
    int parentRowHorizontalVersion, parentHeaderHorizontalVersion;
    int grandparentHeaderHorizontalVersion;

    // Fields used during tree sync
    private Map<Object, GrandparentRow> oldGrandparentToGrandparentRowMap;
    private Map<Object, ParentRow<C>> oldParentToParentRowMap;
    private GrandparentRow lastGrandparentRow;
    private ParentRow<C> lastParentRow;

    public GanttLayoutImpl() {
        setTimeProjector(new TimeProjector<>() {
            @Override
            public double timeToX(T time, boolean start, boolean exclusive) {
                T timeWindowStart = getTimeWindowStart();
                T timeWindowEnd = getTimeWindowEnd();
                if (timeWindowStart == null || timeWindowEnd == null)
                    return 0;
                long totalDays = timeWindowStart.until(timeWindowEnd, ChronoUnit.DAYS) + 1;
                long daysToTime = timeWindowStart.until(time, ChronoUnit.DAYS);
                if (start && exclusive || !start && !exclusive)
                    daysToTime++;
                return getWidth() * daysToTime / totalDays;
            }

            @Override
            public T xToTime(double x) {
                T timeWindowStart = getTimeWindowStart();
                T timeWindowEnd = getTimeWindowEnd();
                if (timeWindowStart == null || timeWindowEnd == null)
                    return null;
                long totalDays = timeWindowStart.until(timeWindowEnd, ChronoUnit.DAYS) + 1;
                return (T) timeWindowStart.plus((long) (x * totalDays / getWidth()), ChronoUnit.DAYS);
            }
        });
        parents.addListener((ListChangeListener<Object>) c -> invalidateProvidedTree());
    }

    @Override
    protected ChildBounds<C, T> createChildLayoutBounds() {
        return new GanttChildBounds<>(this);
    }

    @Override
    public GanttLayoutImpl<C, T> setChildParentReader(Function<C, ?> childParentReader) {
        this.childParentReader = childParentReader;
        invalidateChildrenTree();
        return this;
    }

    @Override
    public GanttLayoutImpl<C, T> setChildGrandparentReader(Function<C, ?> childGrandparentReader) {
        this.childGrandparentReader = childGrandparentReader;
        invalidateChildrenTree();
        return this;
    }

    @Override
    public <P> GanttLayoutImpl<C, T> setParentGrandparentReader(Function<P, ?> parentGrandparentReader) {
        this.parentGrandparentReader = (Function<Object, ?>) parentGrandparentReader;
        invalidateChildrenTree();
        return this;
    }

    @Override
    public GanttLayoutImpl<C, T> setChildTetrisMinWidthReader(Function<C, Double> childTetrisMinWidthReader) {
        this.childTetrisMinWidthReader = childTetrisMinWidthReader;
        return this;
    }

    @Override
    public GanttLayoutImpl<C, T> setGrandparentHeaderPosition(HeaderPosition grandparentHeaderPosition) {
        this.grandparentHeaderPosition = grandparentHeaderPosition;
        return this;
    }

    public HeaderPosition getGrandparentHeaderPosition() {
        return grandparentHeaderPosition;
    }

    public boolean isGrandparentHeaderOnTop() {
        return grandparentHeaderPosition == HeaderPosition.TOP;
    }

    public boolean isGrandparentHeaderOnBottom() {
        return grandparentHeaderPosition == HeaderPosition.BOTTOM;
    }

    public boolean isGrandparentHeaderOnTopOrBottom() {
        return isGrandparentHeaderOnTop() || isGrandparentHeaderOnBottom();
    }

    public boolean isGrandparentHeaderOnLeft() {
        return grandparentHeaderPosition == HeaderPosition.LEFT;
    }

    public boolean isGrandparentHeaderOnRight() {
        return grandparentHeaderPosition == HeaderPosition.RIGHT;
    }

    public boolean isGrandparentHeaderOnLeftOrRight() {
        return isGrandparentHeaderOnLeft() || isGrandparentHeaderOnRight();
    }

    @Override
    public GanttLayoutImpl<C, T> setGrandparentHeaderHeight(double grandparentHeaderHeight) {
        this.grandparentHeaderHeight = grandparentHeaderHeight;
        invalidateVerticalLayout();
        return this;
    }

    @Override
    public double getGrandparentHeaderHeight() {
        return grandparentHeaderHeight;
    }

    public GanttLayoutImpl<C, T> setGrandparentHeaderWidth(double grandparentHeaderWidth) {
        if (grandparentHeaderWidth != this.grandparentHeaderWidth) {
            this.grandparentHeaderWidth = grandparentHeaderWidth;
            invalidateGrandparentHeaderHorizontalVersion();
        }
        return this;
    }

    private void invalidateGrandparentHeaderHorizontalVersion() {
        grandparentHeaderHorizontalVersion++;
        if (!grandparentRows.isEmpty()) {
            if (isGrandparentHeaderOnLeft()) {
                invalidateParentHeaderHorizontalVersion();
            } else if (isGrandparentHeaderOnRight())
                invalidateParentRowHorizontalVersion();
            else
                markLayoutAsDirty();
        }
    }

    public double getGrandparentHeaderWidth() {
        return grandparentHeaderWidth;
    }

    @Override
    public GanttLayoutImpl<C, T> setParentHeaderWidth(double parentHeaderWidth) {
        this.parentHeaderWidth = parentHeaderWidth;
        invalidateParentHeaderHorizontalVersion();
        return this;
    }

    @Override
    public double getParentHeaderWidth() {
        return parentHeaderWidth;
    }

    public double getParentHeaderHeight() {
        return parentHeaderHeight;
    }

    public GanttLayoutImpl<C, T> setParentHeaderHeight(double parentHeaderHeight) {
        this.parentHeaderHeight = parentHeaderHeight;
        return this;
    }

    private void invalidateParentHeaderHorizontalVersion() {
        parentHeaderHorizontalVersion++;
        if (!parentRows.isEmpty()) {
            if (isGrandparentHeaderOnRight())
                invalidateParentRowHorizontalVersion();
            else
                markLayoutAsDirty();
        }
    }

    private void invalidateParentRowHorizontalVersion() {
        parentRowHorizontalVersion++;
        markLayoutAsDirty();
    }

    public HeaderPosition getParentHeaderPosition() {
        return parentHeaderPosition;
    }

    public GanttLayoutImpl<C, T> setParentHeaderPosition(HeaderPosition parentHeaderPosition) {
        this.parentHeaderPosition = parentHeaderPosition;
        return this;
    }

    public boolean isParentHeaderOnTop() {
        return parentHeaderPosition == HeaderPosition.TOP;
    }

    public boolean isParentHeaderOnBottom() {
        return parentHeaderPosition == HeaderPosition.BOTTOM;
    }

    public boolean isParentHeaderOnTopOrBottom() {
        return isParentHeaderOnTop() || isParentHeaderOnBottom();
    }

    public boolean isParentHeaderOnLeft() {
        return parentHeaderPosition == HeaderPosition.LEFT;
    }

    public boolean isParentHeaderOnRight() {
        return parentHeaderPosition == HeaderPosition.RIGHT;
    }

    public boolean isParentHeaderOnLeftOrRight() {
        return isParentHeaderOnLeft() || isParentHeaderOnRight();
    }

    @Override
    public GanttLayoutImpl<C, T> setTetrisPacking(boolean tetrisPacking) {
        this.tetrisPacking = tetrisPacking;
        invalidateChildrenTree();
        return this;
    }

    @Override
    public boolean isTetrisPacking() {
        return tetrisPacking;
    }

    @Override
    public BooleanProperty parentsProvidedProperty() {
        return parentsProvidedProperty;
    }

    @Override
    public GanttLayoutImpl<C, T> setParentsProvided(boolean parentsProvided) {
        parentsProvidedProperty.set(parentsProvided);
        return this;
    }

    @Override
    public boolean isParentsProvided() {
        return parentsProvidedProperty.get();
    }

    @Override
    public <P> ObservableList<P> getParents() {
        return (ObservableList<P>) parents;
    }

    boolean isParentFixedHeight() {
        return isChildFixedHeight() && !isTetrisPacking();
    }

    double getParentFixedHeight() {
        return getChildFixedHeight();
    }

    @Override
    protected void onChildrenChanged(ListChangeListener.Change<? extends C> c) {
        super.onChildrenChanged(c);
        invalidateChildrenTree();
    }

    private void invalidateChildrenTree() {
        childrenTreeVersion++;
        invalidateVerticalLayout(); // for positions that may be recycled
    }

    private void invalidateProvidedTree() {
        providedTreeVersion++;
        invalidateChildrenTree();
    }

    void checkSyncTree() {
        checkSyncProvidedTree();
        checkSyncChildrenTree();
    }

    private void checkSyncChildrenTree() {
        if (builtChildrenTreeVersion != childrenTreeVersion) {
            syncTreeFromChildren();
            builtChildrenTreeVersion = childrenTreeVersion;
        }
    }

    private void checkSyncProvidedTree() {
        if (isParentsProvided() && builtProvidedTreeVersion != providedTreeVersion) {
            syncTreeFromProvidedParents();
            builtProvidedTreeVersion = providedTreeVersion;
        }
    }

    /*******************************************************************************************************************
     ******************************************* Tree synchronisation **************************************************
     ******************************************************************************************************************/

    private void syncTreeFromChildren() {
        if (childrenBounds == null) // may happen when parents are provided before the children
            return;
        startTreeSync(false);
        // Now we begin the loop by iterating over all children (positions)
        childrenBounds.stream().map(lb -> (GanttChildBounds<C,T>) lb).forEach(this::syncChildBranches);
        endTreeSync();
    }

    private void syncTreeFromProvidedParents() {
        // We create a new list of parent rows that will have the same size and order as the provided parents. Therefore:
        // 1) new empty parent rows may appear for parents that had no known children so far
        // 2) existing parent rows may now be in a different order, so we need to update their vertical position
        // 3) Some existing parent rows may disappear (if not listed again in the provided parent for any reason)
        startTreeSync(true);
        parents.forEach(this::syncProvidedParentBranches);
        endTreeSync();
        invalidateChildrenTree();
    }

    private void startTreeSync(boolean syncFromProvidedParents) {
        oldGrandparentToGrandparentRowMap = grandparentToGrandparentRowMap;
        oldParentToParentRowMap = parentToParentRowMap;
        // Same with parentRows, except when parents are provided (as it stays identical in that case)
        if (isParentsProvided() && !syncFromProvidedParents) {
            parentRows.forEach(ParentRow::purgeChildren);
        } else {
            // We clear the list of grandparent rows, as we will refresh it (eventually with same instances)
            grandparentRows.clear();
            parentRows.clear();
            grandparentToGrandparentRowMap = new HashMap<>();
            parentToParentRowMap = new HashMap<>();
        }
        // Clearing the cache (so they keep only live instances), but keeping a reference to old cache to allow recycling
        lastParentRow = null;
        lastGrandparentRow = null;
    }

    private void endTreeSync() {
        grandparentRows.forEach(gpr -> gpr.setRecycling(false));
        parentRows.forEach(pr -> pr.setRecycling(false));
        rowsCount = -1;
        oldGrandparentToGrandparentRowMap = null;
        oldParentToParentRowMap = null;
        lastParentRow = null;
        lastGrandparentRow = null;
    }

    private void syncProvidedParentBranches(Object parent) {
        Object grandparent = parentGrandparentReader != null ? parentGrandparentReader.apply(parent) : null;
        syncGrandparentBranch(grandparent);
        syncParentBranches(parent, true);
    }

    private void syncChildBranches(GanttChildBounds<C, T> cb) {
        // Reading parent & grandparent of that child
        Object parent = childParentReader == null ? null : childParentReader.apply(cb.getObject());
        Object grandparent = childGrandparentReader != null ? childGrandparentReader.apply(cb.getObject()) :
                parent != null && parentGrandparentReader != null ? parentGrandparentReader.apply(parent) : null;
        // Getting or creating the grandparent row if grandparent exists
        if (!isParentsProvided())
            syncGrandparentBranch(grandparent);
        // Now same for parent row, however we accept null parents (children with null parents will be on the same
        // row with no parent on the left). First we check if the parent row already exists
        syncParentBranches(parent, false);
        cb.setParent(parent);
        cb.setParentRow(lastParentRow); // will add this child to parentRow as well
    }

    private void syncGrandparentBranch(Object grandparent) {
        // Getting or creating the grandparent row if grandparent exists
        GrandparentRow grandparentRow = null;
        if (grandparent != null) {
            // Checking if it already exists
            grandparentRow = grandparentToGrandparentRowMap.get(grandparent);
            // Creating it if not
            if (grandparentRow == null) {
                // Trying to recycle a previous instance
                grandparentRow = oldGrandparentToGrandparentRowMap.get(grandparent);
                // If not found, we create a new instance
                if (grandparentRow == null)
                    grandparentRow = new GrandparentRow(grandparent, lastGrandparentRow, this);
                    // Otherwise, we recycle that instance (may still have correct data)
                else if (!grandparentRow.isRecycling()) { // First time we reuse it in that pass
                    grandparentRow.setRecycling(true); // Entering in recycling mode
                    grandparentRow.setAboveGrandparentRow(lastGrandparentRow); // Resetting aboveGrandparentRow
                }
                // Now that we have a grandparent row (either new or recycled), we memorise it in the cache
                grandparentToGrandparentRowMap.put(grandparent, grandparentRow);
                // And add it to the grandparent rows
                grandparentRows.add(grandparentRow);
                // A new grandparent row introduce a break in the parent rows (next parent row should have aboveParentRow = null
                lastParentRow = null;
            }
        }
        lastGrandparentRow = grandparentRow;
    }

    private void syncParentBranches(Object parent, boolean syncFromProvidedParents) {
        ParentRow<C> parentRow = parentToParentRowMap.get(parent); // Note that provided parent are already in that cache
        if (!isParentsProvided() || syncFromProvidedParents) {
            if (parentRow == null) { // We create a new parent row if it doesn't exist
                parentRow = oldParentToParentRowMap.get(parent);
                if (parentRow == null)
                    parentRow = new ParentRow<>(parent, lastParentRow, this);
                else if (!parentRow.isRecycling()) { // First use for recycling
                    parentRow.setRecycling(true);
                    parentRow.setAboveParentRow(lastParentRow);
                }
                parentToParentRowMap.put(parent, parentRow);
                parentRows.add(parentRow);
            }
            parentRow.setGrandparentRow(lastGrandparentRow); // Will add parentRow to grandparent as well
        }
        lastParentRow = parentRow;
    }

    /*******************************************************************************************************************
     ********************************************* Children layout *****************************************************
     ******************************************************************************************************************/

    // Note: the default implementation to layout children horizontally using the time projector is already good for the
    // GanttLayout. We just need to change how to layout children vertically.

    @Override
    public void layoutChildVertically(ChildBounds<C, T> cb) {
        // 1) Computing child height
        GanttChildBounds<C, T> gcb = (GanttChildBounds<C, T>) cb; // Always a GanttLayoutBounds instance
        double childHeight = 0;
        if (isChildFixedHeight())
            childHeight = getChildFixedHeight();
        cb.setHeight(childHeight);
        // 2) Computing child y position
        double vSpacing = getVSpacing();
        int childRowIndex = gcb.getRowIndexInParentRow();
        double y = gcb.getParentRow().getY() // top position of enclosing parent row
                + vSpacing // top spacing
                + (childHeight + vSpacing) * childRowIndex; // vertical shift of that particular child
        if (isParentHeaderOnTop())
            y += parentHeaderHeight;
        cb.setY(y);
    }

    /*******************************************************************************************************************
     ********************************************** Parent layout ******************************************************
     ******************************************************************************************************************/

    // ========================================= Enclosing parent row ==================================================

    // ------------------------------------------- Horizontal layout ---------------------------------------------------
    void layoutParentRowHorizontally(ParentRow<C> pr) {
        // X:
        pr.setX(getParentRowMinX());
        // Width:
        pr.setWidth(getParentRowWidth());
    }

    private double getParentRowMinX() {
        // Other cases (left, top & bottom)
        if (isGrandparentHeaderOnLeft())
            return getGrandparentHeaderMaxX();
        return getGrandparentRowMinX();
    }

    private double getParentRowMaxX() {
        return getParentRowMinX() + getParentRowWidth();
    }

    private double getParentRowWidth() {
        double width = getWidth();
        if (isGrandparentHeaderOnLeftOrRight())
            width -= grandparentHeaderWidth;
        return width;
    }

    // -------------------------------------------- Vertical layout ----------------------------------------------------
    void layoutParentRowVertically(ParentRow<C> pr) {
        // 1) Computing parent row height
        double height;
        if (isParentFixedHeight()) {
            height = getParentFixedHeight();
        } else if (isChildFixedHeight()) {
            double childHeight = getChildFixedHeight();
            double vSpacing = getVSpacing();
            height = vSpacing // top spacing
                    + (childHeight + vSpacing) * pr.getRowsCount(); // total of children in that parent row
         } else
            height = 0; // TODO: what to do in this case?
        if (isParentHeaderOnTopOrBottom())
            height += parentHeaderHeight;
        // 2) Computing parent row y position
        pr.setHeight(height);
        double y;
        if (pr.aboveParentRow != null)
            y = pr.aboveParentRow.getMaxY();
        else if (pr.grandparentRow != null) {
            if (isGrandparentHeaderOnTop())
                y = pr.grandparentRow.getHeader().getMaxY();
            else
                y = pr.grandparentRow.getY();
        } else
            y = getTopY();
        pr.setY(y);
    }

    // ============================================ Parent header ======================================================

    // ------------------------------------------- Horizontal layout ---------------------------------------------------
    void layoutParentHeaderHorizontally(ParentRow<C> pr) {
        MutableBounds header = pr.getHeader();
        // X:
        header.setX(getParentHeaderMinX());
        // Width:
        header.setWidth(getParentHeaderFinalWidth());
    }

    public double getParentHeaderMinX() {
        if (isParentHeaderOnRight())
            return getParentRowMaxX() - getParentHeaderFinalWidth();
        return getParentRowMinX();
    }

    public double getParentHeaderMaxX() {
        return getParentHeaderMinX() + getParentHeaderFinalWidth();
    }

    private double getParentHeaderFinalWidth() {
        if (isParentHeaderOnLeftOrRight())
            return parentHeaderWidth;
        return getParentRowWidth();
    }

    // -------------------------------------------- Vertical layout ----------------------------------------------------
    void layoutParentHeaderVertically(ParentRow<C> pr) {
        MutableBounds header = pr.getHeader();
        // Y:
        double y;
        if (isParentHeaderOnBottom())
            y = pr.getMaxY() - parentHeaderHeight;
        else
            y = pr.getY(); // same as parent row
        header.setY(y);
        // Height:
        double height;
        if (isParentHeaderOnTopOrBottom())
            height = parentHeaderHeight;
        else
            height = pr.getHeight(); // same as parent row
        header.setHeight(height);
    }

    /*******************************************************************************************************************
     ******************************************** Grandparent layout ***************************************************
     ******************************************************************************************************************/

    // ======================================= Enclosing grandparent row ===============================================

    // ------------------------------------------- Horizontal layout ---------------------------------------------------
    void layoutGrandparentRowHorizontally(GrandparentRow gpr) {
        // X:
        gpr.setX(getGrandparentRowMinX());
        // Width:
        gpr.setWidth(getGrandparentRowWidth());
    }

    private double getGrandparentRowMinX() {
        return 0;
    }

    private double getGrandparentRowMaxX() {
        return getGrandparentRowMinX() + getGrandparentRowWidth();
    }

    private double getGrandparentRowWidth() {
        if (isGrandparentHeaderOnTopOrBottom())
            return getWidth();
        return grandparentHeaderWidth;
    }

    // -------------------------------------------- Vertical layout ----------------------------------------------------
    void layoutGrandparentRowVertically(GrandparentRow gpr) {
        // Y:
        double y;
        if (gpr.aboveGrandparentRow == null)
            y = getTopY();
        else
            y = gpr.aboveGrandparentRow.getMaxY();
        gpr.setY(y);
        // Height:
        double height;
        if (isParentFixedHeight()) { // Faster case (we don't need to call each parent row)
            // We compute the height of the content except the header = parents
            height = gpr.parentRows.size() * getParentFixedHeight();
            // If the header is positioned on top or bottom, we need to consider this extra height in the enclosing row
            if (isGrandparentHeaderOnTopOrBottom())
                height += grandparentHeaderHeight;
        } else {
            gpr.validateVerticalLayout(); // not completely true as height is not yet computed, but necessary to avoid
            // infinite loop in the instruction below (will call getY() on this instance again, but not getHeight() => OK)
            height = gpr.getLastParentMaxY() - y;
            if (grandparentHeaderPosition == HeaderPosition.BOTTOM)
                height += grandparentHeaderHeight;
        }
        gpr.setHeight(height);
    }

    // ========================================== Grandparent header ===================================================

    // ------------------------------------------- Horizontal layout ---------------------------------------------------
    void layoutGrandparentHeaderHorizontally(GrandparentRow gpr) { // Note: Always called after layoutGrandparentVertically()
        MutableBounds header = gpr.getHeader();
        // X:
        double x = getGrandparentHeaderMinX();
        header.setX(x);
        // Width:
        double width = getGrandparentHeaderFinalWidth();
        header.setWidth(width);
    }

    double getGrandparentHeaderFinalWidth() {
        if (isGrandparentHeaderOnTopOrBottom())
            return getWidth();
        return grandparentHeaderWidth;
    }

    public double getGrandparentHeaderMinX() {
        if (isGrandparentHeaderOnRight())
            return getWidth() - grandparentHeaderWidth;
        return 0;
    }

    public double getGrandparentHeaderMaxX() {
        return getGrandparentHeaderMinX() + getGrandparentHeaderFinalWidth();
    }

    // -------------------------------------------- Vertical layout ----------------------------------------------------
    void layoutGrandparentHeaderVertically(GrandparentRow gpr) { // Note: Always called after layoutGrandparentVertically()
        MutableBounds header = gpr.getHeader();
        double height;
        if (isGrandparentHeaderOnTopOrBottom())
            height = grandparentHeaderHeight;
        else
            height = gpr.getHeight();
        header.setHeight(height);
        double y = gpr.getY();
        if (grandparentHeaderPosition == HeaderPosition.BOTTOM)
            y += gpr.getHeight() - grandparentHeaderHeight;
        header.setY(y);
    }

    @Override
    public void computeChildRowIndex(ChildBounds<C, T> cb) {
        GanttChildBounds<C, T> gcb = (GanttChildBounds<C, T>) cb;
        cb.setRowIndex(gcb.getRowIndexInParentRow()); // TODO: add previous rows from previous parents
    }

    @Override
    protected double computeHeight() {
        checkSyncTree();
        EnclosingRow<?> lastEnclosingRow = null;
        if (!isParentsProvided() && childrenBounds == null)
            return 0;
        if (!grandparentRows.isEmpty()) // equivalent but more efficient than last parent row
            lastEnclosingRow = grandparentRows.get(grandparentRows.size() - 1);
        else if (!parentRows.isEmpty()) // last parent row ok when no grandparent
            lastEnclosingRow = parentRows.get(parentRows.size() - 1);
        // Returning the bottom of the last enclosing row (or 0 if none)
        return lastEnclosingRow != null ? lastEnclosingRow.getMaxY() : 0;
    }

    @Override
    public int getRowsCount() {
        checkSyncTree();
        if (rowsCount == -1) {
            rowsCount = grandparentRows.size();
            if (isTetrisPacking())
                rowsCount += parentRows.stream().mapToInt(ParentRow::getRowsCount).sum();
            else
                rowsCount += parentRows.size();
        }
        return rowsCount;
    }

    public List<ParentRow<C>> getParentRows() {
        return parentRows;
    }

    public List<GrandparentRow> getGrandparentRows() {
        return grandparentRows;
    }

    @Override
    protected void processVisibleChildrenNow(javafx.geometry.Bounds visibleArea, double layoutOriginX, double layoutOriginY, BiConsumer<C, Bounds> childProcessor) {
        checkSyncTree();
        if (!grandparentRows.isEmpty())
            processVisibleChildrenInGrandparentRows(grandparentRows, visibleArea, layoutOriginX, layoutOriginY, childProcessor);
        else
            processVisibleChildrenInParentRows(parentRows, visibleArea, layoutOriginX, layoutOriginY, childProcessor);
    }

    private void processVisibleChildrenInGrandparentRows(List<GrandparentRow> grandparentRows, javafx.geometry.Bounds visibleArea, double layoutOriginX, double layoutOriginY, BiConsumer<C, Bounds> childProcessor) {
        TimeLayoutUtil.processVisibleObjectBounds(
                grandparentRows,
                true, visibleArea, layoutOriginX, layoutOriginY,
                (grandparentRow, b) -> processVisibleChildrenInParentRows(grandparentRow.getParentRows(), visibleArea, layoutOriginX, layoutOriginY, childProcessor)
        );
    }

    private void processVisibleChildrenInParentRows(List<ParentRow<C>> parentRows, javafx.geometry.Bounds visibleArea, double layoutOriginX, double layoutOriginY, BiConsumer<C, Bounds> childProcessor) {
        TimeLayoutUtil.processVisibleObjectBounds(
                parentRows,
                true, visibleArea, layoutOriginX, layoutOriginY,
                (parentRow, b) -> processVisibleChildrenInParentRow(parentRow, visibleArea, layoutOriginX, layoutOriginY, childProcessor));
    }

    private void processVisibleChildrenInParentRow(ParentRow<C> parentRow, javafx.geometry.Bounds visibleArea, double layoutOriginX, double layoutOriginY, BiConsumer<C, Bounds> childProcessor) {
        TimeLayoutUtil.processVisibleObjectBounds(
                parentRow.getChildrenBounds(),
                false, visibleArea, layoutOriginX, layoutOriginY,
                childProcessor);
    }

}
