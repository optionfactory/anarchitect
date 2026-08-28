package net.optionfactory.anarchitect.fixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.Vector;

public class LegacyCollectionUser {

    public List<String> stack() {
        return new Stack<String>();
    }

    public List<String> vector() {
        return new Vector<String>();
    }

    public List<String> arrayList() {
        return new ArrayList<String>();
    }
}
