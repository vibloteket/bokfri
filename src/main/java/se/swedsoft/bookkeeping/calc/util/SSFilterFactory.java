package se.swedsoft.bookkeeping.calc.util;


import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;


/**
 * User: Andreas Lago
 * Date: 2006-apr-20
 * Time: 11:59:59
 */
public class SSFilterFactory<T> {

    private List<T> iObjects;

    /**
     *
     */
    public SSFilterFactory() {
        iObjects = Collections.emptyList();
    }

    /**
     *
     * @param iObjects
     */
    public SSFilterFactory(List<T> iObjects) {
        this.iObjects = iObjects;
    }

    /**
     *
     * @param iFilter
     */
    public void applyFilter(SSFilter<T> iFilter) {
        iObjects = iObjects.stream()
                .filter(iFilter::applyFilter)
                .collect(Collectors.toList());
    }

    /**
     *
     * @param iFilters
     */
    @SafeVarargs
    public final void applyFilter(SSFilter<T>... iFilters) {
        for (SSFilter<T> iFilter : iFilters) {
            applyFilter(iFilter);

        }
    }

    /**
     *
     * @return
     */
    public List<T> getObjects() {
        return iObjects;
    }

    /**
     *
     * @param iObjects
     */
    public void setObjects(List<T> iObjects) {
        this.iObjects = iObjects;
    }

    // public static <T> void sort(List<T> list, Comparator<? super T> c) {

    /**
     *
     * @param iObjects
     * @param iFilter
     * @return
     */
    public static <T> List<T> doFilter(List<T> iObjects, SSFilter<T> iFilter) {
        SSFilterFactory<T> iFactory = new SSFilterFactory<>(iObjects);

        iFactory.applyFilter(iFilter);

        return iFactory.iObjects;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.calc.util.SSFilterFactory");
        sb.append("{iObjects=").append(iObjects);
        sb.append('}');
        return sb.toString();
    }
}
