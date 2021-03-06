/* 
 * ScreenSlicer (TM)
 * Copyright (C) 2013-2015 Machine Publishers, LLC
 * ops@machinepublishers.com | screenslicer.com | machinepublishers.com
 * Cincinnati, Ohio, USA
 *
 * You can redistribute this program and/or modify it under the terms of the GNU Affero General Public
 * License version 3 as published by the Free Software Foundation.
 *
 * "ScreenSlicer", "jBrowserDriver", "Machine Publishers", and "automatic, zero-config web scraping"
 * are trademarks of Machine Publishers, LLC.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License version 3 for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License version 3 along with this
 * program. If not, see http://www.gnu.org/licenses/
 * 
 * For general details about how to investigate and report license violations, please see
 * https://www.gnu.org/licenses/gpl-violation.html and email the author, ops@machinepublishers.com
 */
package com.screenslicer.core.scrape.trainer;

public class TrainerProceed {
  private Visitor visitor;

  public static interface Visitor {
    void init();

    int visit(int curTrainingData);

    int trainingDataSize();
  }

  public TrainerProceed(Visitor visitor) {
    this.visitor = visitor;
    this.visitor.init();
    perform();
  }

  private void perform() {
    int success = 0;
    for (int i = 0; i < visitor.trainingDataSize(); i++) {
      if (visitor.visit(i) == 0) {
        ++success;
      }
    }
    System.out.println("Success: " + success);
  }
}
