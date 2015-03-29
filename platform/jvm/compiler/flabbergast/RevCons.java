package flabbergast;

import java.util.ArrayList;
import java.util.List;

class RevCons<T> {
	private final T head;
	private final int index;
	private final RevCons<T> tail;

	RevCons(T item, RevCons<T> tail) {
		head = item;
		this.tail = tail;
		index = tail == null ? 0 : (tail.index + 1);
	}

	private void assign(List<T> array) {
		array.add(index, head);
		if (tail != null) {
			tail.assign(array);
		}
	}

	List<T> toList() {
		List<T> array = new ArrayList<T>(index + 1);
		assign(array);
		return array;
	}
}