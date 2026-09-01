/*
 * Copyright (c) 2026 AltaStata Inc. All rights reserved.
 *
 * This software is dual-licensed. It is licensed under the Business Source License 1.1 
 * (BSL) for open use and evaluation, with an eventual transition to the Apache 2.0 
 * license on the Change Date.
 * 
 * PATENT NOTICE: Protected by US Patent No. 10,693,660.
 *
 * For the full license text, see the LICENSE.md file in the root of the repository,
 * or https://github.com/AltaStata/sovereign-data-fabric/blob/main/LICENSE.md
 */

package com.altastata.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.scene.control.ComboBox;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Utility class to enhance standard JavaFX ComboBoxes with real-time autocompletion capabilities.
 * Filters dropdown items dynamically on keystrokes and controls popover behaviors.
 * 
 * <p>Adaptation based on: http://stackoverflow.com/questions/19924852/autocomplete-combobox-in-javafx</p>
 */
public class AutocompleteCombobox {

	/**
	 * Functional interface used to determine whether a given autocomplete data object
	 * matches the user's typed search query text.
	 *
	 * @param <T> The type of objects to compare.
	 */
	public interface AutoCompleteComparator<T> {
		/**
		 * Checks if the provided object matches the typed prefix string.
		 *
		 * @param typedText       The characters currently entered in the editor field.
		 * @param objectToCompare The candidate data item.
		 * @return {@code true} if there is a match, {@code false} otherwise.
		 */
		boolean matches(String typedText, T objectToCompare);
	}

	/**
	 * Configures a standard ComboBox to perform real-time autocompletion as the user types.
	 * Registers event handlers for keystrokes (navigation arrows, delete, backspace, and text entries),
	 * filtering candidates according to the provided comparator callback and showing the dropdown list.
	 *
	 * @param <T>              The item type.
	 * @param comboBox         The JavaFX {@link ComboBox} to enhance.
	 * @param comparatorMethod The matching logic callback to filter entries.
	 */
	public static <T> void autoCompleteComboBoxPlus(ComboBox<T> comboBox, AutoCompleteComparator<T> comparatorMethod) {
		ObservableList<T> data = comboBox.getItems();

		comboBox.setEditable(true);
		comboBox.getEditor().focusedProperty().addListener(observable -> {
			if (comboBox.getSelectionModel().getSelectedIndex() < 0) {
				comboBox.getEditor().setText(null);
			}
		});
		comboBox.addEventHandler(KeyEvent.KEY_PRESSED, t -> comboBox.hide());
		comboBox.addEventHandler(KeyEvent.KEY_RELEASED, new EventHandler<KeyEvent>() {

			private boolean moveCaretToPos = false;
			private int caretPos;

			/**
			 * Handles key event for the autocomplete dropdown.
			 *
			 * @param event the key event triggered by user input
			 */
			@Override
			public void handle(KeyEvent event) {
				if (event.getCode() == KeyCode.UP) {
					caretPos = -1;
					moveCaret(comboBox.getEditor().getText().length());
					return;
				} else if (event.getCode() == KeyCode.DOWN) {
					if (!comboBox.isShowing()) {
						comboBox.show();
					}
					caretPos = -1;
					moveCaret(comboBox.getEditor().getText().length());
					return;
				} else if (event.getCode() == KeyCode.BACK_SPACE) {
					moveCaretToPos = true;
					caretPos = comboBox.getEditor().getCaretPosition();
				} else if (event.getCode() == KeyCode.DELETE) {
					moveCaretToPos = true;
					caretPos = comboBox.getEditor().getCaretPosition();
				} else if (event.getCode() == KeyCode.ENTER) {
					return;
				}

				if (event.getCode() == KeyCode.RIGHT || event.getCode() == KeyCode.LEFT
						|| event.getCode().equals(KeyCode.SHIFT) || event.getCode().equals(KeyCode.CONTROL)
						|| event.isControlDown() || event.getCode() == KeyCode.HOME || event.getCode() == KeyCode.END
						|| event.getCode() == KeyCode.TAB) {
					return;
				}

				ObservableList<T> list = FXCollections.observableArrayList();
				for (T aData : data) {
					if (aData != null && comboBox.getEditor().getText() != null
							&& comparatorMethod.matches(comboBox.getEditor().getText(), aData)) {
						list.add(aData);
					}
				}
				String t = comboBox.getEditor().getText();

				comboBox.setItems(list);
				comboBox.getEditor().setText(t);
				if (!moveCaretToPos) {
					caretPos = -1;
				}
				if (t != null) {
					moveCaret(t.length());
				}
				if (!list.isEmpty()) {
					comboBox.show();
				}
			}

			/**
			 * Moves the caret to the appropriate position.
			 *
			 * @param textLength the current text length to position caret at end if needed
			 */
			private void moveCaret(int textLength) {
				if (caretPos == -1) {
					comboBox.getEditor().positionCaret(textLength);
				} else {
					comboBox.getEditor().positionCaret(caretPos);
				}
				moveCaretToPos = false;
			}
		});
	}

	/**
	 * Safely retrieves the currently selected value from the given ComboBox.
	 *
	 * @param <T>      The item type.
	 * @param comboBox The target {@link ComboBox}.
	 * @return The selected item of type T, or {@code null} if nothing is selected or the index is out of bounds.
	 */
	public static <T> T getComboBoxValue(ComboBox<T> comboBox) {
		if (comboBox.getSelectionModel().getSelectedIndex() < 0) {
			return null;
		} else {
			return comboBox.getItems().get(comboBox.getSelectionModel().getSelectedIndex());
		}
	}
}
