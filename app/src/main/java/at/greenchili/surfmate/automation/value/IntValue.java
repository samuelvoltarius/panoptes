package at.greenchili.surfmate.automation.value;

import at.greenchili.surfmate.automation.type.EnumType;

public class IntValue extends BaseValue<Integer> {
    public static final EnumType COMPARATORS = new EnumType(
            new Label("comparator", "automation.cmp_label"),
            new Label("eq", "automation.cmp_eq"),
            new Label("neq", "automation.cmp_neq"),
            new Label("gt", "automation.cmp_gt"),
            new Label("lt", "automation.cmp_lt"),
            new Label("gte", "automation.cmp_gte"),
            new Label("lte", "automation.cmp_lte"));
    private final Integer value;

    /**
     * An int value with methods to compare to other values
     *
     * @param value The integer to store
     */
    public IntValue(Integer value) {
        this.value = value;
    }

    /**
     * The comparators for this int
     *
     * @return The comparators for this int
     */
    public EnumType getComparators() {
        return COMPARATORS;
    }

    /**
     * The int which was stored
     *
     * @return The int which was stored
     */
    public Integer getValue() {
        return this.value;
    }

    /**
     * Compare the int to another int using a specific comparator
     *
     * @param other      The value to compare to this one
     * @param comparator The comparator e.g. `eq` for equals
     * @return The return result of the comparator, null if it can't be compared
     */
    public Boolean compareValue(Integer other, String comparator) {
        if (getValue() == null || other == null || comparator == null) return null;

        int compare = getValue().compareTo(other);
        switch (comparator.toLowerCase()) {
            case "eq":
                return compare == 0;
            case "neq":
                return compare != 0;
            case "gt":
                return compare > 0;
            case "lt":
                return compare < 0;
            case "gte":
                return compare >= 0;
            case "lte":
                return compare <= 0;
            default:
                return null;
        }
    }
}
