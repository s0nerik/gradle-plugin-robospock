/*
 * Copyright 2014 Centril / Mazdak Farrokhzad <twingoow@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.centril.robospock.internal.graph

import spock.lang.Specification

/**
 * Tests {@link DepthFirstIterator}.
 *
 * @author Centril <twingoow@gmail.com> / Mazdak Farrokhzad.
 * @since 2014-12-27
 */
class DepthFirstIteratorSpecification extends GraphIteratorSpecification {
	def "dfsIterator"() {
		when:
			graph()
		then:
			r == (new DepthFirstIterator( g, s )).collect()
		where:
			s	|| r
			1	|| [1, 2, 6, 10, 14, 11, 7, 3, 8, 12, 13, 4, 9, 5]
			2	|| [2, 6, 10, 14, 11, 7]
			3	|| [3, 7, 14, 8, 12, 13]
			4	|| [4, 8, 12, 14, 13, 9]
			5	|| [5]
			6	|| [6, 10, 14, 11]
			7	|| [7, 14]
			8	|| [8, 12, 14, 13]
			9	|| [9]
			10	|| [10, 14]
			11	|| [11, 14]
			12	|| [12, 14]
			13	|| [13, 14]
	}
}