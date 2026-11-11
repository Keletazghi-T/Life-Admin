package com.lifeadmin.service;
import com.lifeadmin.dto.ReceiptExtractionDetails;
import com.lifeadmin.entity.ReceiptCategory;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

/** Conservative, deterministic rules over actual PDF text. No merchant/category lookup or AI. */
@Component
public class ReceiptTextParser {
 private static final Pattern MERCHANT=Pattern.compile("(?i)^(?:merchant|store|business|seller)\\s*:\\s*(.*)$");
 private static final Pattern DATE=Pattern.compile("(?i)^(?:purchase date|transaction date|date)(?:\\s*[:#]\\s*|\\s+)(.+)$");
 private static final Pattern TOTAL=Pattern.compile("(?i)^(grand total|total paid|amount paid|total amount|total|amount due)(?:\\s*:\\s*|\\s+)(.+)$");
 private static final Pattern MONEY=Pattern.compile("(?i)^(?:(?:USD|EUR|GBP|CAD|AUD|JPY|CHF|NZD|US\\$|[$€£])\\s*)?((?:[0-9]{1,12}|[1-9][0-9]{0,2}(?:,[0-9]{3}){1,3})\\.[0-9]{2})(?:\\s*(?:USD|EUR|GBP|CAD|AUD|JPY|CHF|NZD))?$");
 private static final Pattern CURRENCY=Pattern.compile("(?i)\\b(?:USD|EUR|GBP|CAD|AUD|JPY|CHF|NZD)\\b|\\bUS\\$");
 public ReceiptExtractionDetails parse(String text){
  var lines=text.lines().map(String::strip).filter(s->!s.isBlank()).map(s->s.replaceAll("\\s+"," ")).toList();
  String merchant=merchant(lines),currency=currency(text);LocalDate date=date(lines);BigDecimal amount=total(lines);ReceiptCategory category=category(lines);
  var missing=new ArrayList<String>();if(merchant==null)missing.add("merchant");if(date==null)missing.add("purchase date");if(amount==null)missing.add("total amount");if(currency==null)missing.add("currency");if(category==null)missing.add("category");
  String status=missing.isEmpty()?"EXTRACTED":missing.size()==5?"NO_MATCH":"PARTIAL";
  String reason=missing.isEmpty()?null:"Could not determine automatically: "+String.join(", ",missing)+". Missing, ambiguous or unsupported values were left blank.";
  return ReceiptExtractionDetails.result(merchant,date,amount,currency,category,status,"PDF_TEXT",reason);
 }
 private String merchant(List<String> lines){
  Set<String> named=new LinkedHashSet<>();boolean labelled=false;
  for(String line:lines){var m=MERCHANT.matcher(line);if(m.matches()){labelled=true;if(!validMerchant(m.group(1)))return null;named.add(m.group(1).strip());}}
  if(labelled)return named.size()==1?named.iterator().next():null;
  // Only an actual first-line business heading, with recognizable receipt fields below it.
  if(lines.size()<2 || lines.stream().noneMatch(l->TOTAL.matcher(l).matches()))return null;
  String first=lines.getFirst();return validMerchant(first)?first:null;
 }
 private boolean validMerchant(String value){
  if(value.length()<2 || value.length()>200 || !value.matches(".*[\\p{L}].*") || value.matches(".*[0-9:@/\\\\].*"))return false;
  return !value.toLowerCase(Locale.ROOT).matches(".*\\b(receipt|invoice|thank|welcome|customer|cashier|transaction|subtotal|total|date|payment|order|balance|change|unknown|missing|items|description|qty|quantity|street|road|avenue)\\b.*") && !value.equalsIgnoreCase("N/A");
 }
 private LocalDate date(List<String> lines){
  var candidates=new ArrayList<String>();
  for(String line:lines){var m=DATE.matcher(line);if(m.matches())candidates.add(m.group(1));}
  if(candidates.isEmpty())return null;
  Set<LocalDate> values=new HashSet<>();for(String value:candidates){var date=parseDate(value);if(date==null)return null;values.add(date);}return values.size()==1?values.iterator().next():null;
 }
 private LocalDate parseDate(String input){
  // A trailing printed time is allowed, but other trailing words aren't silently discarded.
  String value=input.replaceFirst("(?i)[ T]+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?(?:\\s*[AP]M)?$","").strip();LocalDate date=null;
  try{
   if(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))date=LocalDate.parse(value,DateTimeFormatter.ISO_LOCAL_DATE);
   else if(value.matches("[0-9]{1,2}/[0-9]{1,2}/[0-9]{4}")){
    var p=value.split("/");int a=Integer.parseInt(p[0]),b=Integer.parseInt(p[1]),y=Integer.parseInt(p[2]);
    if(a<=12 && b<=12 && a!=b)return null; // No locale guesses for 03/04/2026.
    date=a>12?LocalDate.of(y,b,a):LocalDate.of(y,a,b);
   }else for(String pattern:List.of("MMMM d, uuuu","MMM d, uuuu","d MMMM uuuu","d MMM uuuu"))try{
    date=LocalDate.parse(value,new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT));break;
   }catch(DateTimeParseException ignored){}
  }catch(DateTimeException|NumberFormatException e){return null;}
  return date==null || date.getYear()<1900 || date.isAfter(LocalDate.now(ZoneOffset.UTC))?null:date;
 }
 private BigDecimal total(List<String> lines){
  // Do not choose a subtotal, tax, tip, tendered amount or change as the receipt total.
  var values=new ArrayList<BigDecimal>();int selectedPriority=-1;
  for(String line:lines){var m=TOTAL.matcher(line);if(!m.matches())continue;int priority=List.of("grand total","total paid","amount paid").contains(m.group(1).toLowerCase(Locale.ROOT))?1:0;
   if(priority<selectedPriority)continue;if(priority>selectedPriority){selectedPriority=priority;values.clear();}
   var amount=MONEY.matcher(m.group(2));BigDecimal parsed=null;
   if(amount.matches())try{parsed=new BigDecimal(amount.group(1).replace(",",""));if(parsed.signum()<=0 || parsed.precision()>14)parsed=null;}catch(NumberFormatException ignored){}
   values.add(parsed);
  }
  if(values.isEmpty() || values.contains(null))return null;var unique=new HashSet<>(values);return unique.size()==1?unique.iterator().next():null;
 }
 private String currency(String text){var m=CURRENCY.matcher(text);Set<String> codes=new HashSet<>();while(m.find())codes.add(m.group().equalsIgnoreCase("US$")?"USD":m.group().toUpperCase(Locale.ROOT));return codes.size()==1?codes.iterator().next():null;}
 private ReceiptCategory category(List<String> lines){
  Set<ReceiptCategory> values=new HashSet<>();boolean found=false;
  for(String line:lines)if(line.toLowerCase(Locale.ROOT).startsWith("category:")){found=true;try{values.add(ReceiptCategory.valueOf(line.substring(9).strip().toUpperCase(Locale.ROOT)));}catch(IllegalArgumentException e){return null;}}
  return found && values.size()==1?values.iterator().next():null;
 }
}
