package org.openntf.howyabean.foerderung;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import lotus.domino.Database;
import lotus.domino.Document;
import lotus.domino.DocumentCollection;
import lotus.domino.NotesException;

import com.ibm.xsp.extlib.util.ExtLibUtil;

/**
 * Backoffice prototype for the Burgenland "Schulische Sport- und Projekttage" funding.
 *
 * One document per application (form "FoerderAntrag"). The status machine and the
 * amount calculation follow the published guidelines:
 *
 *   stage 1..3 from the net household income table (adults x children),
 *   amount from stage x school level (up to 4 / from 5) x event type,
 *   +30 % when the child is a multiple and the siblings take part.
 *
 * Roles are a demo switch for the prototype: Sachbearbeiter (inspect, request a
 * correction), Genehmiger (approve, reject), Finanzabteilung (batch, release).
 */ 
public class FoerderungApp implements Serializable { 

	private static final long serialVersionUID = 1L;
	public static final String FORM = "FoerderAntrag";

	public static final String S_EINGEREICHT = "Eingereicht";
	public static final String S_IN_PRUEFUNG = "In Pruefung";
	public static final String S_NACHBESSERUNG = "Nachbesserung";
	public static final String S_BEWILLIGT = "Bewilligt";
	public static final String S_ABGELEHNT = "Abgelehnt";
	public static final String S_STORNIERT = "Storniert"; 

	
	private static final double[][] ADULTS_1 = {
		{ 1770, 1950, 2130 }, { 2290, 2520, 2750 }, { 2810, 3090, 3370 },
		{ 3330, 3660, 3990 }, { 3840, 4230, 4610 } };
	private static final double[][] ADULTS_2 = {
		{ 2400, 2640, 2880 }, { 2920, 3210, 3500 }, { 3430, 3780, 4120 },
		{ 3950, 4350, 4740 }, { 4470, 4920, 5370 } };
	private static final double[] WINTER_LOW = { 100, 80, 60 };
	private static final double[] OTHER_LOW = { 80, 60, 40 };
	private static final double[] WINTER_HIGH = { 135, 110, 85 };
	private static final double[] OTHER_HIGH = { 110, 85, 60 };

	private String role = "Sachbearbeiter";
	private String message;

	// ---------------------------------------------------------------- rules

	public static double[] thresholds(int adults, int children) {
		int a = Math.max(1, adults);
		int c = Math.max(1, children);
		double[][] table = a == 1 ? ADULTS_1 : ADULTS_2;
		int index = Math.min(c, 5) - 1;
		double extraChildren = 350.0 * Math.max(0, c - 5);
		double extraAdults = 600.0 * Math.max(0, a - 2);
		return new double[] { table[index][0] + extraChildren + extraAdults,
				table[index][1] + extraChildren + extraAdults,
				table[index][2] + extraChildren + extraAdults };
	}

	public static int stage(double income, int adults, int children) {
		double[] t = thresholds(adults, children);
		if (income <= t[0]) return 1;
		if (income <= t[1]) return 2;
		if (income <= t[2]) return 3;
		return 0;
	}

	public static double amount(int stage, int level, boolean winter, boolean multiple) {
		if (stage < 1 || stage > 3) return 0;
		double base;
		if (level <= 4) {
			base = winter ? WINTER_LOW[stage - 1] : OTHER_LOW[stage - 1];
		} else {
			base = winter ? WINTER_HIGH[stage - 1] : OTHER_HIGH[stage - 1];
		}
		return round2(base * (multiple ? 1.3 : 1.0));
	}

	public static double round2(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	// ---------------------------------------------------------------- list

	public List<Antrag> getAntraege() {
		List<Antrag> result = new ArrayList<Antrag>();
		DocumentCollection all = null;
		Document doc = null;
		try {
			Database db = ExtLibUtil.getCurrentDatabase();
			all = db.getAllDocuments();
			doc = all.getFirstDocument();
			while (doc != null) {
				Document next = all.getNextDocument(doc);
				if (FORM.equals(doc.getItemValueString("Form"))) {
					result.add(snapshot(doc));
				}
				doc.recycle();
				doc = next;
			}
		} catch (NotesException e) {
			message = "Liste konnte nicht gelesen werden: " + e.getMessage();
		} finally {
			recycle(doc);
			recycle(all);
		}
		result.sort((a, b) -> b.getCreated().compareTo(a.getCreated()));
		return result;
	}

	private Antrag snapshot(Document doc) throws NotesException {
		Antrag a = new Antrag();
		a.unid = doc.getUniversalID();
		a.status = doc.getItemValueString("status");
		a.applicant = doc.getItemValueString("applicant");
		a.child = doc.getItemValueString("child");
		a.school = doc.getItemValueString("school");
		a.schoolType = doc.getItemValueString("schoolType");
		a.level = (int) doc.getItemValueDouble("level");
		a.winter = "1".equals(doc.getItemValueString("winter"));
		a.costs = doc.getItemValueDouble("costs");
		a.income = doc.getItemValueDouble("income");
		a.adults = (int) doc.getItemValueDouble("adults");
		a.children = (int) doc.getItemValueDouble("children");
		a.stage = (int) doc.getItemValueDouble("stage");
		a.amount = doc.getItemValueDouble("amount");
		a.due = doc.getItemValueString("due");
		a.created = doc.getItemValueString("created");
		a.multiple = "1".equals(doc.getItemValueString("multiple"));
		a.stageSuggestion = stage(a.income, a.adults, a.children);
		return a;
	}

	// ---------------------------------------------------------------- seeds

	public void seedDemo() {
		try {
			Database db = ExtLibUtil.getCurrentDatabase();
			createApplication(db, "Mustermann Max", "Mustermann Mia", "Volksschule Eisenstadt",
					"Volksschule", 3, true, 240, 1800, 1, 1, false);
			createApplication(db, "Beispiel Anna", "Beispiel Tim", "Mittelschule Oberwart",
					"Mittelschule", 6, false, 310, 2600, 2, 2, true);
			createApplication(db, "Hofer Petra", "Hofer Lea", "Gymnasium Eisenstadt",
					"Gymnasium (Unterstufe)", 5, true, 480, 5200, 1, 1, false);
			message = "Drei Demo-Anträge angelegt.";
		} catch (NotesException e) {
			message = "Demo-Daten konnten nicht angelegt werden: " + e.getMessage();
		}
	}

	public void seedMore() {
		String[][] demos = {
			{ "D1", "Maria Huber", "Huber Luis", "Volksschule Mattersburg", "Volksschule", "2", "1", "220", "1700", "1", "2", "0", "Eingereicht" },
			{ "D2", "Stefan Berger", "Berger Emma", "Mittelschule Oberwart", "Mittelschule", "7", "0", "340", "3000", "2", "2", "0", "In Pruefung" },
			{ "D3", "Andrea Weber", "Weber Noah", "Volksschule Neusiedl", "Volksschule", "4", "0", "180", "2100", "1", "1", "0", "In Pruefung" },
			{ "D4", "Thomas Koenig", "Koenig Mia", "Gymnasium Eisenstadt", "Gymnasium (Unterstufe)", "8", "1", "520", "6400", "2", "3", "0", "Abgelehnt" },
			{ "D5", "Julia Schuster", "Schuster Elias", "Mittelschule Guessing", "Mittelschule", "5", "1", "380", "2400", "1", "2", "1", "Bewilligt" },
			{ "D6", "Anna Pichler", "Pichler Sophie", "Volksschule Eisenstadt", "Volksschule", "1", "0", "150", "1600", "1", "1", "0", "Nachbesserung" },
			{ "D7", "Klaus Graf", "Graf Felix", "Polytechnische Schule Oberwart", "Politechnische Schule", "9", "0", "260", "2000", "1", "2", "0", "Eingereicht" },
			{ "D8", "Petra Hoffmann", "Hoffmann Lena", "Mittelschule Oberpullendorf", "Mittelschule", "6", "0", "290", "3300", "2", "2", "0", "Bewilligt" },
			{ "D9", "Michael Bauer", "Bauer Jonas", "Volksschule Jennersdorf", "Volksschule", "3", "1", "210", "2500", "2", "2", "0", "Eingereicht" },
			{ "D10", "Sabine Richter", "Richter Marie", "Gymnasium Oberwart", "Gymnasium (Unterstufe)", "5", "0", "320", "1900", "1", "1", "0", "Eingereicht" } };
		int added = 0;
		try {
			Database db = ExtLibUtil.getCurrentDatabase();
			java.util.Set<String> existing = demoKeys(db);
			for (String[] d : demos) {
				if (existing.contains(d[0])) {
					continue;
				}
				createDemo(db, d);
				added++;
			}
			message = added == 0 ? "Demo-Anträge waren bereits vorhanden." : added + " Demo-Anträge ergänzt.";
		} catch (NotesException e) {
			message = "Demo-Daten konnten nicht ergänzt werden: " + e.getMessage();
		}
	}

	private java.util.Set<String> demoKeys(Database db) throws NotesException {
		java.util.Set<String> keys = new java.util.HashSet<String>();
		DocumentCollection all = db.getAllDocuments();
		Document doc = all.getFirstDocument();
		while (doc != null) {
			Document next = all.getNextDocument(doc);
			if (FORM.equals(doc.getItemValueString("Form"))) {
				String key = doc.getItemValueString("demoKey");
				if (key != null && !key.isEmpty()) {
					keys.add(key);
				}
			}
			doc.recycle();
			doc = next;
		}
		return keys;
	}

	private void createDemo(Database db, String[] d) throws NotesException {
		String status = d[12];
		int level = Integer.parseInt(d[5]);
		boolean winter = "1".equals(d[6]);
		double costs = Double.parseDouble(d[7]);
		double income = Double.parseDouble(d[8]);
		int adults = Integer.parseInt(d[9]);
		int children = Integer.parseInt(d[10]);
		boolean multiple = "1".equals(d[11]);
		int stufe = stage(income, adults, children);
		Document doc = db.createDocument();
		try {
			doc.replaceItemValue("Form", FORM);
			doc.replaceItemValue("demoKey", d[0]);
			doc.replaceItemValue("status", status);
			doc.replaceItemValue("applicant", d[1]);
			doc.replaceItemValue("child", d[2]);
			doc.replaceItemValue("school", d[3]);
			doc.replaceItemValue("schoolType", d[4]);
			doc.replaceItemValue("level", level);
			doc.replaceItemValue("winter", winter ? "1" : "0");
			doc.replaceItemValue("costs", costs);
			doc.replaceItemValue("income", income);
			doc.replaceItemValue("adults", adults);
			doc.replaceItemValue("children", children);
			doc.replaceItemValue("multiple", multiple ? "1" : "0");
			doc.replaceItemValue("created", now());
			doc.replaceItemValue("stage", stufe);
			double betrag = 0;
			if (S_BEWILLIGT.equals(status)) {
				betrag = amount(stufe, level, winter, multiple);
				doc.replaceItemValue("decided", now());
				doc.appendItemValue("history", now() + " | Demo | Bewilligt: Stufe " + stufe + ", " + money(betrag));
			} else {
				doc.appendItemValue("history", now() + " | Demo | Eingang erfasst");
			}
			doc.replaceItemValue("amount", betrag);
			if (S_NACHBESSERUNG.equals(status)) {
				doc.replaceItemValue("due", dueInDays(7));
			}
			doc.save();
		} finally {
			doc.recycle();
		}
	}
	private void createApplication(Database db, String applicant, String child, String school,
			String type, int level, boolean winter, double costs, double income, int adults,
			int children, boolean multiple) throws NotesException {
		Document doc = db.createDocument();
		try {
			doc.replaceItemValue("Form", FORM);
			doc.replaceItemValue("status", S_EINGEREICHT);
			doc.replaceItemValue("applicant", applicant);
			doc.replaceItemValue("child", child);
			doc.replaceItemValue("school", school);
			doc.replaceItemValue("schoolType", type);
			doc.replaceItemValue("level", level);
			doc.replaceItemValue("winter", winter ? "1" : "0");
			doc.replaceItemValue("costs", costs);
			doc.replaceItemValue("income", income);
			doc.replaceItemValue("adults", adults);
			doc.replaceItemValue("children", children);
			doc.replaceItemValue("multiple", multiple ? "1" : "0");
			doc.replaceItemValue("created", now());
			doc.replaceItemValue("stage", stage(income, adults, children));
			doc.replaceItemValue("amount", 0);
			doc.appendItemValue("history", now() + " | Eingang erfasst");
			doc.save();
		} finally {
			doc.recycle();
		}
	}

	// -------------------------------------------------------------- actions

	public void action(String action, String unid) {
		Document doc = null;
		try {
			if (!allowed(action)) {
				message = "Die Rolle " + role + " darf diese Aktion nicht ausführen.";
				return;
			}
			Database db = ExtLibUtil.getCurrentDatabase();
			doc = db.getDocumentByUNID(unid);
			if (doc == null) {
				message = "Antrag nicht gefunden.";
				return;
			}
			String status = doc.getItemValueString("status");
			switch (action) {
			case "pruefen":
				doc.replaceItemValue("status", S_IN_PRUEFUNG);
				appendHistory(doc, "In Prüfung genommen");
				break;
			case "nachbesserung":
				doc.replaceItemValue("status", S_NACHBESSERUNG);
				doc.replaceItemValue("due", dueInDays(14));
				appendHistory(doc, "Nachbesserung angefordert, Frist " + doc.getItemValueString("due"));
				break;
			case "wiedervorlage":
				doc.replaceItemValue("status", S_IN_PRUEFUNG);
				doc.replaceItemValue("due", "");
				appendHistory(doc, "Nachbesserung eingelangt");
				break;
			case "bewilligen": {
				int stufe = stage(doc.getItemValueDouble("income"),
						(int) doc.getItemValueDouble("adults"), (int) doc.getItemValueDouble("children"));
				if (stufe < 1) {
					message = "Keine Förderstufe – Einkommen über der Grenze, bitte ablehnen.";
					return;
				}
				double betrag = amount(stufe, (int) doc.getItemValueDouble("level"),
						"1".equals(doc.getItemValueString("winter")),
						"1".equals(doc.getItemValueString("multiple")));
				doc.replaceItemValue("status", S_BEWILLIGT);
				doc.replaceItemValue("amount", betrag);
				doc.replaceItemValue("decided", now());
				appendHistory(doc, "Bewilligt: Stufe " + stufe + ", " + money(betrag));
				break;
			}
			case "ablehnen":
				doc.replaceItemValue("status", S_ABGELEHNT);
				appendHistory(doc, "Abgelehnt");
				break;
			case "storno":
				doc.replaceItemValue("status", S_STORNIERT);
				appendHistory(doc, "Storniert (vorher " + status + ")");
				message = "Storniert. Schreiben und ggf. Rückforderung sind als nächster Schritt vorgesehen.";
				break;
			default:
				message = "Unbekannte Aktion: " + action;
				return;
			}
			doc.save();
			if (message == null || message.isEmpty()) {
				message = "Aktion '" + action + "' ausgeführt.";
			}
		} catch (NotesException e) {
			message = "Aktion fehlgeschlagen: " + e.getMessage();
		} finally {
			recycle(doc);
		}
	}

	public void updateDerived(String unid) {
		Document doc = null;
		try {
			if (unid == null || unid.isEmpty()) {
				return;
			}
			doc = ExtLibUtil.getCurrentDatabase().getDocumentByUNID(unid);
			if (doc == null) {
				return;
			}
			int stufe = stage(doc.getItemValueDouble("income"),
					(int) doc.getItemValueDouble("adults"), (int) doc.getItemValueDouble("children"));
			doc.replaceItemValue("stage", stufe);
			if (!S_BEWILLIGT.equals(doc.getItemValueString("status"))) {
				doc.replaceItemValue("amount", 0);
			}
			doc.save();
		} catch (NotesException e) {
			message = "Nachberechnung fehlgeschlagen: " + e.getMessage();
		} finally {
			recycle(doc);
		}
	}
	private boolean allowed(String action) {
		switch (action) {
		case "pruefen":
		case "nachbesserung":
		case "wiedervorlage":
			return "Sachbearbeiter".equals(role);
		case "bewilligen":
		case "ablehnen":
			return "Genehmiger".equals(role);
		case "storno":
			return "Sachbearbeiter".equals(role) || "Genehmiger".equals(role);
		default:
			return false;
		}
	}

	private void appendHistory(Document doc, String text) throws NotesException {
		doc.appendItemValue("history", now() + " | " + role + " | " + text);
	}

	// ------------------------------------------------------------ helpers

	private static String now() {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
	}

	private static String dueInDays(int days) {
		Calendar c = Calendar.getInstance();
		c.add(Calendar.DAY_OF_MONTH, days);
		return new SimpleDateFormat("yyyy-MM-dd").format(c.getTime());
	}

	private static String money(double value) {
		return String.format("%.2f EUR", value);
	}

	private static void recycle(Object object) {
		if (object instanceof lotus.domino.Base) {
			try {
				((lotus.domino.Base) object).recycle();
			} catch (NotesException ignored) {
				// prototype
			}
		}
	}

	// ------------------------------------------------------------ getters

	public String getRole() { return role; }
	public void setRole(String role) { this.role = role; }
	public String getMessage() { return message; }
	public void setMessage(String message) { this.message = message; }

	public int getCount() { return getAntraege().size(); }

	public double getSummeBewilligt() {
		double sum = 0;
		for (Antrag a : getAntraege()) {
			if (S_BEWILLIGT.equals(a.getStatus())) sum += a.getAmount();
		}
		return round2(sum);
	}

	public String getSummeBewilligtText() {
		return String.format("%.2f", getSummeBewilligt());
	}

	// ------------------------------------------------------------- value

	public static class Antrag implements Serializable {
		private static final long serialVersionUID = 1L;

		String unid;
		String status;
		String applicant;
		String child;
		String school;
		String schoolType;
		int level;
		boolean winter;
		double costs;
		double income;
		int adults;
		int children;
		int stage;
		int stageSuggestion;
		double amount;
		String due;
		String created;
		boolean multiple;

		public String getUnid() { return unid; }
		public String getStatus() { return status; }
		public String getApplicant() { return applicant; }
		public String getChild() { return child; }
		public String getSchool() { return school; }
		public String getSchoolType() { return schoolType; }
		public int getLevel() { return level; }
		public boolean isWinter() { return winter; }
		public String getEventType() { return winter ? "Wintersport" : "Sonstiges"; }
		public double getCosts() { return costs; }
		public String getCostsText() { return String.format("%.2f", costs); }
		public double getIncome() { return income; }
		public String getIncomeText() { return String.format("%.0f", income); }
		public int getAdults() { return adults; }
		public int getChildren() { return children; }
		public int getStage() { return stage; }
		public int getStageSuggestion() { return stageSuggestion; }
		public String getStageText() { return stage < 1 ? "–" : String.valueOf(stage); }
		public double getAmount() { return amount; }
		public String getAmountText() { return String.format("%.2f", amount); }
		public String getDue() { return due == null || due.isEmpty() ? "" : due; }
		public String getCreated() { return created; }
		public boolean isMultiple() { return multiple; }
		public boolean isOpen() { return !S_ABGELEHNT.equals(status) && !S_STORNIERT.equals(status); }
		public boolean isApproved() { return S_BEWILLIGT.equals(status); }
	}
}
