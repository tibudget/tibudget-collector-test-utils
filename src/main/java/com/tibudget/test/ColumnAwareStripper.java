package com.tibudget.test;

import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.List;

public class ColumnAwareStripper extends PDFTextStripper {

    public ColumnAwareStripper() throws IOException {
        super();
        setSortByPosition(true); // Important for proper ordering
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {

        float x = textPositions.get(0).getXDirAdj();
        float y = textPositions.get(0).getYDirAdj();

        // Adjust these thresholds after logging real values
        if (x > 300 && x < 380) {
            System.out.println("DEBIT at Y=" + y + " : " + text);
        } else if (x >= 380) {
            System.out.println("CREDIT at Y=" + y + " : " + text);
        } else {
            System.out.println("OTHER at Y=" + y + " : " + text);
        }
    }
}
